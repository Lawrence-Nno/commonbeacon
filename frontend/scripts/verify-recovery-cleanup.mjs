import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";

const project = process.env.COMMONBEACON_E2E_PROJECT ?? "commonbeacon-e2e-cleanup-" + randomUUID().slice(0, 8);
assert.match(project, /^commonbeacon-e2e-[a-z0-9-]+$/);
function clean() {
  for (const name of [project, project + "-imports"]) for (const type of ["container", "network", "volume"]) {
    const result = spawnSync("docker", [type, "ls", ...(type === "container" ? ["-a"] : []), "-q", "--filter", "label=com.docker.compose.project=" + name], { encoding: "utf8", timeout: 30000 });
    assert.equal(result.status, 0); assert.equal(result.stdout.trim(), "", name + " leaked " + type);
  }
}
clean();
// A correctly prefixed name is not permission to adopt someone else's resources.
const sentinel = project + "-adoption-" + randomUUID();
const absent = spawnSync("docker", ["volume", "inspect", sentinel], { encoding: "utf8", timeout: 30000 });
assert.equal(absent.status, 1); assert.match(absent.stderr, /no such volume/i);
const created = spawnSync("docker", ["volume", "create", "--label", "com.docker.compose.project=" + project, sentinel], { encoding: "utf8", timeout: 30000 });
assert.equal(created.status, 0);
try {
  const refused = spawnSync(process.execPath, [fileURLToPath(new URL("test-e2e.mjs", import.meta.url)), "transfer-recovery.spec.ts"], {
    env: { ...process.env, COMMONBEACON_E2E_PROJECT: project }, encoding: "utf8", timeout: 30000,
  });
  assert.equal(refused.status, 1); assert.match(refused.stderr, /Disposable project is not clean/);
  assert.equal(spawnSync("docker", ["volume", "inspect", sentinel], { encoding: "utf8", timeout: 30000 }).status, 0, "Runner removed a resource it did not create");
} finally {
  assert.equal(spawnSync("docker", ["volume", "rm", sentinel], { encoding: "utf8", timeout: 30000 }).status, 0);
}
clean();
const result = spawnSync(process.execPath, [fileURLToPath(new URL("test-e2e.mjs", import.meta.url)), "transfer-recovery.spec.ts", "--inject-recovery-failure"], {
  env: { ...process.env, COMMONBEACON_E2E_PROJECT: project }, encoding: "utf8", timeout: 900000, maxBuffer: 16 * 1024 * 1024,
});
assert.equal(result.status, 1, result.stderr);
assert.match(result.stderr, /Injected recovery failure after both persistent stacks started/);
clean();
console.log("Existing project resources were refused and preserved. Injected failure returned nonzero; both created stacks and their volumes were reclaimed.");
