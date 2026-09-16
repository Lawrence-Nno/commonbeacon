import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const frontend = fileURLToPath(new URL("../", import.meta.url));
const project = "commonbeacon-e2e-" + randomUUID().slice(0, 8);
const args = [
  "compose",
  "--project-name",
  project,
  "--env-file",
  ".env.example",
  "-f",
  "compose.e2e.yaml",
];
// Explicit project/file on every call; no volumes exist and no development cleanup is possible.
const docker = (extra, capture = false) =>
  spawnSync("docker", [...args, ...extra], {
    cwd: root,
    stdio: capture ? "pipe" : "inherit",
    encoding: "utf8",
    timeout: 600000,
  });
let code = 1;
try {
  const up = docker(["up", "-d", "--build", "--wait", "--wait-timeout", "180"]);
  if (up.error || up.status !== 0)
    throw new Error("Disposable browser stack failed to start.");
  const result = spawnSync(
    process.execPath,
    ["node_modules/@playwright/test/cli.js", "test", ...process.argv.slice(2)],
    {
      cwd: frontend,
      stdio: "inherit",
      env: {
        ...process.env,
        COMMONBEACON_E2E: "isolated",
        DEMO_PASSWORD: "disposable-e2e-demo-password",
      },
    },
  );
  code = result.status ?? 1;
} catch (error) {
  console.error(error.message);
} finally {
  const logs = docker(["logs", "--no-color"], true);
  mkdirSync(new URL("../test-results/", import.meta.url), { recursive: true });
  writeFileSync(
    new URL("../test-results/backend-compose.log", import.meta.url),
    (logs.stdout ?? "") + (logs.stderr ?? ""),
  );
  const cleanup = docker(["down", "--remove-orphans"]);
  if (cleanup.error || cleanup.status !== 0) {
    console.error("Cleanup failed for disposable project " + project);
    code = 1;
  }
}
process.exitCode = code;
