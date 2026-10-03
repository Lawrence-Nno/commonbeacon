import { expect, test, request, type APIRequestContext } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { randomUUID, randomBytes, createHash } from "node:crypto";
import { request as httpRequest } from "node:http";
import { fileURLToPath } from "node:url";

// Fault injection exists only in this disposable database, never in the application.
const root = fileURLToPath(new URL("../../", import.meta.url));
const project = process.env.COMMONBEACON_E2E_PROJECT!;
const password = process.env.DEMO_PASSWORD!;
const origin = (target: boolean) => `http://127.0.0.1:${target ? 4176 : 4173}`;
function compose(target: boolean, args: string[]) {
  expect(project).toMatch(/^commonbeacon-e2e-[a-z0-9-]+$/);
  expect(process.env.COMMONBEACON_E2E_GROUP).toBe("recovery");
  const result = spawnSync("docker", ["compose", "--project-name", project + (target ? "-imports" : ""), "--env-file", ".env.example",
    "-f", "compose.e2e.yaml", "-f", "compose.erasure-e2e.yaml", ...(target ? ["-f", "compose.import-e2e.yaml"] : []),
    "-f", "compose.recovery-e2e.yaml", ...args], { cwd: root, encoding: "utf8", timeout: 240000 });
  expect(result.status, result.stderr).toBe(0); return result.stdout.trim();
}
function sql(target: boolean, statement: string) {
  return compose(target, ["exec", "-T", "db", "psql", "-U", "e2e", "-d", "commonbeacon_e2e", "-v", "ON_ERROR_STOP=1", "-Atc", statement]);
}
async function get(c: APIRequestContext, path: string) {
  const r = await c.get(path); expect(r.status(), path).toBe(200); return r.json();
}
async function post(c: APIRequestContext, path: string, data: unknown, status = 200) {
  const csrf = await get(c, "/api/v1/auth/csrf");
  const r = await c.post(path, { data, headers: { [csrf.headerName]: csrf.token, "Idempotency-Key": randomUUID() } });
  expect(r.status(), path + ": " + await r.text()).toBe(status); return r.json();
}
async function session(target: boolean) {
  const c = await request.newContext({ baseURL: origin(target), timeout: 15000 });
  const csrf = await get(c, "/api/v1/auth/csrf");
  expect((await c.post("/api/v1/auth/login", { form: { email: target ? "bootstrap@example.test" : "avery.admin@example.test", password }, headers: { [csrf.headerName]: csrf.token } })).status()).toBe(200);
  return c;
}
const jobPath = (id: string) => `/api/v1/admin/data/jobs/${id}`;
const importPath = (id: string) => `/api/v1/admin/data/imports/${id}`;
async function grant(c: APIRequestContext, scope: string) {
  return (await post(c, "/api/v1/account/data/reauthentication", { password, scope })).token;
}
async function state(c: APIRequestContext, id: string, expected: string) {
  await expect.poll(async () => (await get(c, jobPath(id))).state, { timeout: 90000, intervals: [500, 1000] }).toBe(expected);
}
function pause(target: boolean, table: string, condition = "true") {
  sql(target, `CREATE FUNCTION stage16_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF ${condition} THEN PERFORM set_config('application_name','stage16-pause',true); PERFORM pg_sleep(120); END IF;
    RETURN NEW; END $$;
    CREATE TRIGGER stage16_pause BEFORE INSERT OR UPDATE ON ${table} FOR EACH ROW EXECUTE FUNCTION stage16_pause()`);
}
async function paused(target: boolean, timeout = 20000) {
  await expect.poll(() => sql(target, "SELECT count(*) FROM pg_stat_activity WHERE application_name='stage16-pause' AND wait_event='PgSleep'"), { timeout, intervals: [200] }).toBe("1");
}
function unpause(target: boolean, table: string) {
  // Release only the artificial sleeping connection after killing its owning process.
  sql(target, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='stage16-pause'");
  sql(target, `DROP TRIGGER stage16_pause ON ${table}; DROP FUNCTION stage16_pause()`);
}
function kill(target: boolean, service = "backend") { compose(target, ["kill", "-s", "SIGKILL", service]); }
function start(target: boolean) { compose(target, ["up", "-d", "--wait", "--wait-timeout", "180"]); }
function expireLease(target: boolean, id: string) {
  // Synthetic clock advance only after the old process is dead. No production timeout is changed.
  sql(target, `UPDATE transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id='${id}'`);
}
async function download(c: APIRequestContext, id: string) {
  const ticket = await post(c, jobPath(id) + "/download-ticket", { recentAuthGrant: await grant(c, "DOWNLOAD") });
  const response = await c.get(jobPath(id) + "/download", { headers: { "X-Download-Ticket": ticket.token } });
  expect(response.status()).toBe(200); expect(response.headers()["cache-control"]).toBe("no-store");
  const data = await response.body();
  expect((await c.get(jobPath(id) + "/download", { headers: { "X-Download-Ticket": ticket.token } })).status()).toBe(403);
  return data;
}
async function upload(c: APIRequestContext, id: string, bytes: Buffer) {
  const csrf = await get(c, "/api/v1/auth/csrf");
  expect((await c.put(importPath(id) + "/archive", { data: bytes, headers: { [csrf.headerName]: csrf.token, "Content-Type": "application/zip" } })).status()).toBe(200);
  await expect.poll(async () => (await c.get(importPath(id) + "/inspection")).status(), { timeout: 30000 }).toBe(200);
  expect((await get(c, importPath(id) + "/inspection")).valid).toBe(true);
}
async function newImport(c: APIRequestContext) {
  return (await post(c, "/api/v1/admin/data/imports", { formatVersion: 1, recentAuthGrant: await grant(c, "IMPORT_UPLOAD") }, 201)).id as string;
}
async function review(c: APIRequestContext, id: string) {
  await post(c, importPath(id) + "/dry-run", { expectedVersion: (await get(c, jobPath(id))).version }, 202);
}
async function confirm(c: APIRequestContext, id: string) {
  await state(c, id, "READY_TO_COMMIT"); const r = await get(c, importPath(id) + "/review");
  expect(r.eligible).toBe(true);
  await post(c, importPath(id) + "/confirm", { expectedVersion: (await get(c, jobPath(id))).version,
    reviewDigest: r.reviewDigest, archiveDigest: r.archiveDigest, targetGeneration: r.targetGeneration,
    acknowledgedPrivateContent: true, acknowledgedInactiveAuthors: true, acknowledgedWarnings: r.warnings,
    recentAuthGrant: await grant(c, "IMPORT_COMMIT") }, 202);
}

test("persistent A/B recover interrupted upload, staging and READY; activation crash never publishes partial data", async ({ browserName }, info) => {
  expect(browserName).toBe("chromium");
  test.setTimeout(600000);
  let a = await session(false), b = await session(true);
  const evidence: string[] = [];
  try {
    // Pause publication after the complete ZIP is written but before READY commits.
    pause(false, "transfer_completion");
    const exported = await post(a, "/api/v1/admin/data/exports", { includeContacts: true, includeModerationHistory: true,
      acknowledgedPrivateContent: true, recentAuthGrant: await grant(a, "COMPANY_EXPORT") }, 202);
    await paused(false); kill(false); unpause(false, "transfer_completion"); expireLease(false, exported.id); start(false);
    expect((await a.get("/api/v1/auth/me")).status()).toBe(401); await a.dispose(); a = await session(false);
    await state(a, exported.id, "READY");
    expect(Number(sql(false, `SELECT attempts FROM transfer_job WHERE id='${exported.id}'`))).toBe(2);
    const archive = await download(a, exported.id), digest = createHash("sha256").update(archive).digest("hex");
    evidence.push("SIGKILL after ZIP write/before READY: newer fenced attempt published; old session and consumed ticket denied");
    kill(false, "db"); start(false);
    expect(createHash("sha256").update(await download(a, exported.id)).digest("hex")).toBe(digest);
    evidence.push("PostgreSQL SIGKILL at READY: identical private archive bytes after WAL recovery");

    const id = await newImport(b), csrf = await get(b, "/api/v1/auth/csrf");
    const cookies = (await b.storageState()).cookies.map(c => `${c.name}=${c.value}`).join("; ");
    const partial = httpRequest(origin(true) + importPath(id) + "/archive", { method: "PUT", headers: {
      Cookie: cookies, [csrf.headerName]: csrf.token, "Content-Type": "application/zip", "Content-Length": archive.length,
    } });
    partial.on("error", () => {}); partial.on("response", r => r.resume()); partial.write(archive.subarray(0, 64));
    try {
      await expect.poll(() => sql(true, `SELECT count(*) FROM transfer_artifact WHERE job_id='${id}' AND state='WRITING'`), { timeout: 15000 }).toBe("1");
      kill(true);
    } finally { partial.destroy(); }
    expireLease(true, id); start(true); await b.dispose(); b = await session(true);
    await upload(b, id, archive);
    evidence.push("SIGKILL during streamed upload: full retry inspected; incomplete artifact never available");

    pause(true, "transfer_stage"); await review(b, id); await paused(true);
    kill(true); unpause(true, "transfer_stage"); expireLease(true, id); start(true); await b.dispose(); b = await session(true);
    await state(b, id, "READY_TO_COMMIT");
    expect((await get(b, importPath(id) + "/review")).eligible).toBe(true);
    expect(sql(true, "SELECT count(*) FROM question")).toBe("0");
    expect(sql(true, "SELECT count(*) FROM (SELECT job_id,entity,source_id FROM transfer_stage GROUP BY 1,2,3 HAVING count(*)>1) d")).toBe("0");
    evidence.push("SIGKILL during staging transaction: fenced retry produced one eligible review and no domain writes");

    pause(true, "transfer_completion"); await confirm(b, id); await paused(true);
    // This boundary has executed every domain insert inside the uncommitted transaction.
    expect(sql(true, "SELECT count(*) FROM question")).toBe("0");
    kill(true); unpause(true, "transfer_completion"); expireLease(true, id); start(true); await b.dispose(); b = await session(true);
    await state(b, id, "FAILED");
    expect((await get(b, jobPath(id))).errorCode).toBe("ACTIVATION_RECONCILIATION_REQUIRED");
    expect(sql(true, "SELECT (SELECT count(*) FROM question)+(SELECT count(*) FROM imported_record)+(SELECT count(*) FROM transfer_completion)")).toBe("0");
    evidence.push("SIGKILL immediately before activation commit: complete rollback, reconciliation required, no automatic reinsertion");

    // A deliberate fresh review/import, not an automatic retry of uncertain activation.
    const fresh = await newImport(b); await upload(b, fresh, archive); await review(b, fresh); await confirm(b, fresh); await state(b, fresh, "COMPLETED");
    const receipt = await get(b, importPath(fresh) + "/reconciliation");
    expect(receipt.counts.questions.created).toBe(Number(sql(false, "SELECT count(*) FROM question")));
    expect(sql(true, "SELECT count(*) FROM app_user WHERE account_state='IMPORTED_INACTIVE' AND (email IS NOT NULL OR password_hash IS NOT NULL OR role<>'MEMBER')")).toBe("0");
    const before = sql(true, "SELECT md5(string_agg(row_to_json(q)::text,'' ORDER BY id)) FROM question q");
    kill(true, "db"); start(true);
    expect(sql(true, "SELECT md5(string_agg(row_to_json(q)::text,'' ORDER BY id)) FROM question q")).toBe(before);
    expect(await get(b, importPath(fresh) + "/reconciliation")).toEqual(receipt);
    evidence.push("Fresh deliberate activation completed once; database crash preserved graph fingerprint and durable receipt");
    // Force only this test's terminal retention deadlines, then run startup reconciliation.
    kill(true); sql(true, "UPDATE transfer_artifact SET expires_at=clock_timestamp()-interval '1 second'; UPDATE transfer_dry_run SET expires_at=clock_timestamp()-interval '1 second'");
    pause(true, "transfer_artifact", "NEW.state='DELETED'"); start(true); await paused(true);
    kill(true); unpause(true, "transfer_artifact"); start(true);
    await expect.poll(() => sql(true, "SELECT count(*) FROM transfer_artifact WHERE state<>'DELETED'"), { timeout: 30000 }).toBe("0");
    expect(sql(true, "SELECT count(*) FROM transfer_stage")).toBe("0");
    expect(sql(true, "SELECT coalesce(sum(reserved_bytes),0) FROM transfer_job")).toBe("0");
    expect(compose(true, ["exec", "-T", "backend", "sh", "-c", "find /var/lib/commonbeacon/transfers -type f ! -name .store.lock | wc -l"])).toBe("0");
    expect(sql(true, "SELECT md5(string_agg(row_to_json(q)::text,'' ORDER BY id)) FROM question q")).toBe(before);
    evidence.push("SIGKILL after physical deletion/before cleanup metadata commit: restart reclaimed partial/upload/staging bytes and reservations; activated data retained");

    await b.dispose(); b = await session(true);
    const preview = await post(b, "/api/v1/erasure/previews", { scope: "COMPANY" });
    pause(true, "erasure_job", "NEW.state='RUNNING' AND NEW.phase=15");
    const token = randomBytes(32).toString("base64url");
    await post(b, `/api/v1/erasure/${preview.id}/confirm`, { digest: preview.digest, phrase: preview.phrase,
      acknowledgements: preview.acknowledgements, receiptToken: token, recentAuthGrant: await grant(b, "COMPANY_ERASURE") }, 202);
    await paused(true, 45000);
    expect((await b.get("/api/v1/boards")).status()).toBe(503);
    kill(true); kill(true, "db");
    compose(true, ["up", "-d", "--wait", "--wait-timeout", "180", "db"]);
    unpause(true, "erasure_job"); start(true);
    await expect.poll(async () => {
      const r = await b.get(`/api/v1/erasure/receipts/${preview.id}`, { headers: { "X-Erasure-Receipt": token } });
      expect(r.status()).toBe(200); expect(r.headers()["cache-control"]).toBe("no-store"); return (await r.json()).state;
    }, { timeout: 90000, intervals: [1000] }).toBe("COMPLETED");
    expect(sql(true, "SELECT (SELECT count(*) FROM question)+(SELECT count(*) FROM imported_record)+(SELECT count(*) FROM transfer_job)")).toBe("0");
    expect(sql(true, "SELECT count(*) FROM app_user")).toBe("1");
    expect(sql(true, "SELECT count(*) FROM erasure_tombstone")).toBe("1");
    evidence.push("Backend and PostgreSQL SIGKILL during company erasure: maintenance barrier held, receipt resumed, one bootstrap retained and tombstone preserved");
  } finally {
    await a.dispose(); await b.dispose();
    await info.attach("recovery-boundaries", { body: JSON.stringify({ syntheticLeaseExpiry: true, evidence }, null, 2), contentType: "application/json" });
  }
});
