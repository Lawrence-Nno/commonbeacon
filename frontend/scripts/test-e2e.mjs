import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const frontend = fileURLToPath(new URL("../", import.meta.url));
const project =
  process.env.COMMONBEACON_E2E_PROJECT ??
  "commonbeacon-e2e-" + randomUUID().slice(0, 8);
if (!/^commonbeacon-e2e-[a-z0-9-]+$/.test(project))
  throw new Error("Invalid isolated project name.");
const args = [
  "compose",
  "--project-name",
  project,
  "--env-file",
  ".env.example",
  "-f",
  "compose.e2e.yaml",
  "-f",
  "compose.erasure-e2e.yaml",
];
// Explicit project/file on every call; no volumes exist and no development cleanup is possible.
const docker = (extra, capture = false) =>
  spawnSync("docker", [...args, ...extra], {
    cwd: root,
    stdio: capture ? "pipe" : "inherit",
    encoding: "utf8",
    timeout: 600000,
  });
const targetArgs = [...args];
targetArgs[targetArgs.indexOf(project)] = project + "-imports";
targetArgs.push("-f", "compose.import-e2e.yaml");
const targetDocker = (extra, input) => spawnSync("docker", [...targetArgs, ...extra], {
  cwd: root, stdio: "pipe", encoding: "utf8", timeout: 600000, input,
});
function startSource() {
  const up = docker(["up", "-d", "--build", "--wait", "--wait-timeout", "180"]);
  if (up.error || up.status !== 0) throw new Error("Disposable browser stack failed to start.");
}
function startTarget() {
  const targetUp = targetDocker(["up", "-d", "--build", "--wait", "--wait-timeout", "180"]);
  if (targetUp.error || targetUp.status !== 0) throw new Error("Disposable import target failed to start: " + targetUp.stderr);
  // Copy only the disposable demo password hash into one fresh bootstrap admin.
  // No production credentials or persistent volumes are involved.
  const seed = docker(["exec", "-T", "db", "psql", "-U", "e2e", "-d", "commonbeacon_e2e", "-Atc",
    "SELECT format('INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (%L,%L,%L,%L,%L);',gen_random_uuid(),'bootstrap@example.test','Bootstrap',password_hash,'ADMINISTRATOR') FROM app_user WHERE email='avery.admin@example.test'"], true);
  if (seed.status !== 0 || !seed.stdout?.startsWith("INSERT INTO")) throw new Error("Disposable bootstrap template unavailable.");
  const seeded = targetDocker(["exec", "-T", "db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "e2e", "-d", "commonbeacon_e2e"], seed.stdout);
  if (seeded.status !== 0) throw new Error("Disposable import bootstrap failed.");
}
function run(group) {
  const result = spawnSync(
    process.execPath,
    ["node_modules/@playwright/test/cli.js", "test", `--output=test-results/${group}`, ...process.argv.slice(2)],
    {
      cwd: frontend,
      stdio: "inherit",
      env: {
        ...process.env,
        COMMONBEACON_E2E: "isolated",
        COMMONBEACON_E2E_PROJECT: project,
        COMMONBEACON_E2E_GROUP: group,
        PLAYWRIGHT_HTML_OUTPUT_DIR: `playwright-report/${group}`,
        DEMO_PASSWORD: "disposable-e2e-demo-password",
      },
    },
  );
  return result.status ?? 1;
}
function saveLogs(group) {
  const logs = docker(["logs", "--no-color"], true);
  mkdirSync(new URL(`../test-results/${group}/`, import.meta.url), { recursive: true });
  writeFileSync(new URL(`../test-results/${group}/backend-compose.log`, import.meta.url), (logs.stdout ?? "") + (logs.stderr ?? ""));
}
const selectors = process.argv.slice(2).filter(arg => arg.includes(".spec"));
const groups = selectors.length ? [...new Set(selectors.map(arg => arg.includes("erasure.spec") ? "erasure" : arg.includes("discourse-import.spec") ? "discourse" : arg.includes("import-activation.spec") ? "imports" : "community"))] : ["community", "imports", "discourse", "erasure"];
let code = 0, currentGroup = groups[0];
try {
  for (const [index, group] of groups.entries()) {
    currentGroup = group;
    if (index > 0) {
      // Fresh disposable tmpfs DB and process-local rate limiters between suites.
      // Exercise real production limits without carrying another suite's quotas.
      const reset = docker(["down", "--remove-orphans"]);
      if (reset.error || reset.status !== 0) throw new Error("Disposable source reset failed.");
      const resetTarget = targetDocker(["down", "--remove-orphans"]);
      if (resetTarget.error || resetTarget.status !== 0) throw new Error("Disposable import target reset failed.");
    }
    startSource();
    if (group === "imports" || group === "discourse") startTarget();
    code = Math.max(code, run(group));
    saveLogs(group);
  }
} catch (error) {
  console.error(error.message);
  code = 1;
} finally {
  try {
    saveLogs(currentGroup);
    const targetLogs = targetDocker(["logs", "--no-color"]);
    mkdirSync(new URL("../test-results/imports/", import.meta.url), { recursive: true });
    writeFileSync(new URL("../test-results/imports/import-compose.log", import.meta.url), (targetLogs.stdout ?? "") + (targetLogs.stderr ?? ""));
  } catch (error) {
    console.error("Could not save logs:", error.message);
    code = 1;
  }
  const cleanup = docker(["down", "--remove-orphans"]);
  const targetCleanup = targetDocker(["down", "--remove-orphans"]);
  if (targetCleanup.error || targetCleanup.status !== 0) { console.error("Import test target cleanup failed."); code = 1; }
  if (cleanup.error || cleanup.status !== 0) {
    console.error("Cleanup failed for disposable project " + project);
    code = 1;
  }
}
process.exitCode = code;
