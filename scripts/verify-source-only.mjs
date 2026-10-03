import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// No development environment file or volume is used. This checks the committed
// source, not uncommitted runtime changes; the revision is recorded in its output.
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = `cb-source-${randomUUID().slice(0, 8)}`;
const root = mkdtempSync(join(tmpdir(), 'commonbeacon-source-'));
const source = join(root, 'source');
const env = join(root, 'test.env');
const base = 'http://127.0.0.1:4181';
const password = `Synthetic-${randomUUID()}`;
const testEnvironment = {
  POSTGRES_PASSWORD: randomUUID(), POSTGRES_DB: 'commonbeacon', POSTGRES_USER: 'commonbeacon',
  FRONTEND_PORT: '4181', DEMO_SEED_ENABLED: 'false', DEMO_PASSWORD: '',
  COMPANY_ERASURE_ENABLED: 'false', ERASURE_BACKUP_RETENTION_DAYS: '30',
};
const cookies = new Map();
const proof = { project, demoSeeding: false, administratorProvisioning: 'unavailable', checks: [] };
let ownsProject = false;

function command(bin, args, options = {}) {
  const result = spawnSync(bin, args, { cwd: repo, encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${bin} ${args[0]} failed (${result.status}): ${result.stderr ?? ''}`);
  return result.stdout?.trim() ?? '';
}
function resources(kind) {
  return command('docker', [kind, 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`]);
}
function compose(args, options) {
  return command('docker', ['compose', '--project-name', project, '--env-file', env,
    '-f', join(source, 'compose.yaml'), '-f', join(source, 'compose.transfers.yaml'), ...args],
  { ...options, env: { ...process.env, ...testEnvironment } });
}
async function request(path, { method = 'GET', body, headers = {}, status = 200 } = {}) {
  const response = await fetch(`${base}/api/v1${path}`, { method, body,
    headers: { Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; '), ...headers },
    signal: AbortSignal.timeout(30_000) });
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0];
    const equals = pair.indexOf('=');
    cookies.set(pair.slice(0, equals), pair.slice(equals + 1));
  }
  assert.equal(response.status, status, `${method} ${path}`);
  return response;
}
async function mutate(path, body, options = {}) {
  const csrf = await (await request('/auth/csrf')).json();
  return request(path, { method: 'POST', body: JSON.stringify(body), ...options,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, ...options.headers } });
}

try {
  for (const kind of ['container', 'network', 'volume']) assert.equal(resources(kind), '', 'project must be unused');
  ownsProject = true;
  mkdirSync(source);
  proof.revision = command('git', ['rev-parse', 'HEAD']);
  const archive = join(root, 'source.tar');
  command('git', ['archive', '--format=tar', `--output=${archive}`, proof.revision]);
  command('tar', ['-xf', archive, '-C', source]);
  for (const excluded of ['.git', '.env', 'frontend/node_modules', 'backend/target']) {
    assert.equal(existsSync(join(source, excluded)), false, `source copy excludes ${excluded}`);
  }
  writeFileSync(env, Object.entries(testEnvironment).map(([key, value]) => `${key}=${value}\n`).join(''));
  compose(['config', '--quiet']);
  console.log(`Building source-only revision ${proof.revision} in ${project}`);
  compose(['up', '-d', '--build', '--wait', '--wait-timeout', '180'], { stdio: 'inherit' });
  assert.equal((await fetch(base, { signal: AbortSignal.timeout(10_000) })).status, 200);
  assert.deepEqual(await (await request('/boards')).json(), []);
  proof.checks.push('clean container build and healthy homepage; no demo boards');
  await mutate('/auth/register', { email: 'source-only@example.test', displayName: 'Source Only', password }, { status: 201 });
  const csrf = await (await request('/auth/csrf')).json();
  const me = await (await request('/auth/login', { method: 'POST',
    body: new URLSearchParams({ email: 'source-only@example.test', password }),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token } })).json();
  assert.equal(me.role, 'MEMBER');
  await request('/admin/data/jobs', { status: 403 });
  proof.checks.push('registration creates MEMBER; administrator transfer access denied');
  await request('/erasure/previews', { method: 'POST', body: JSON.stringify({ scope: 'ACCOUNT' }),
    headers: { 'Content-Type': 'application/json' }, status: 403 });
  const preview = await (await mutate('/erasure/previews', { scope: 'ACCOUNT' })).json();
  assert.equal(preview.scope, 'ACCOUNT');
  proof.checks.push('erasure preview requires CSRF; authenticated preview succeeds without erasing data');
  proof.erasurePreview = preview;
  const grant = await (await mutate('/account/data/reauthentication', { password, scope: 'PERSONAL_EXPORT', ignoredExtension: true })).json();
  let job = await (await mutate('/account/data/exports', { recentAuthGrant: grant.token },
    { status: 202, headers: { 'Idempotency-Key': randomUUID() } })).json();
  const deadline = Date.now() + 90_000;
  while (job.state !== 'READY' && Date.now() < deadline) {
    assert.ok(!['FAILED', 'CANCELLED', 'EXPIRED'].includes(job.state), `export state ${job.state}`);
    await new Promise(resolveWait => setTimeout(resolveWait, 1000));
    job = await (await request(`/account/data/jobs/${job.id}`)).json();
  }
  assert.equal(job.state, 'READY');
  const downloadGrant = await (await mutate('/account/data/reauthentication', { password, scope: 'DOWNLOAD' })).json();
  const ticket = await (await mutate(`/account/data/jobs/${job.id}/download-ticket`, { recentAuthGrant: downloadGrant.token, ignoredExtension: true })).json();
  const response = await request(`/account/data/jobs/${job.id}/download`, { headers: { 'X-Download-Ticket': ticket.token } });
  const bytes = Buffer.from(await response.arrayBuffer());
  assert.equal(response.headers.get('content-type'), 'application/zip');
  assert.equal(Number(response.headers.get('content-length')), bytes.length);
  assert.equal(bytes.subarray(0, 2).toString(), 'PK');
  await request(`/account/data/jobs/${job.id}/download`, { headers: { 'X-Download-Ticket': ticket.token }, status: 403 });
  proof.checks.push('personal export READY; protected ZIP download; ticket replay denied');
  proof.checks.push('reauthentication and download-ticket ignore unknown fields');
  proof.download = { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
  proof.job = job;
} finally {
  if (ownsProject && existsSync(join(source, 'compose.yaml')) && existsSync(env)) {
    compose(['down', '--volumes', '--remove-orphans', '--timeout', '30'], { stdio: 'inherit' });
    for (const kind of ['container', 'network', 'volume']) assert.equal(resources(kind), '', `no leaked ${kind}`);
    proof.cleanup = 'all isolated project resources removed';
  }
  const allowed = resolve(tmpdir()) + sep;
  assert.ok(resolve(root).startsWith(allowed) && dirname(root) === resolve(tmpdir()));
  rmSync(root, { recursive: true, force: true });
}
mkdirSync(join(repo, 'backend', 'target'), { recursive: true });
writeFileSync(join(repo, 'backend', 'target', 'source-only-verification.json'), JSON.stringify(proof, null, 2) + '\n');
console.log(JSON.stringify(proof, null, 2));
