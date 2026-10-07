import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

function sql(statement: string) {
  const project = process.env.COMMONBEACON_E2E_PROJECT ?? "";
  if (!/^commonbeacon-e2e-[a-z0-9-]+$/.test(project)) throw new Error("Disposable project required");
  const result = spawnSync("docker", ["compose", "--project-name", project, "--env-file", ".env.example", "-f", "compose.e2e.yaml", "exec", "-T", "db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "e2e", "-d", "commonbeacon_e2e", "-Atc", statement],
    { cwd: fileURLToPath(new URL("../../", import.meta.url)), encoding: "utf8", timeout: 15000 });
  expect(result.status, result.stderr).toBe(0);
}

test("pending sessions retain privacy access and require fresh login after account activation", async ({ page }, testInfo) => {
  const id = randomUUID(), email = `pending-${id}@example.test`, origin = "http://127.0.0.1:4173";
  // Simulate future lifecycle transitions only inside this disposable qualification stack.
  sql(`INSERT INTO app_user(id,email,display_name,password_hash,account_state)
    SELECT '${id}','${email}','Pending browser member',password_hash,'PENDING_VERIFICATION'
    FROM app_user WHERE email='alex.member@example.test'`);
  async function login() {
    await page.goto(origin + "/login");
    await page.getByLabel("Email address").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  }
  await login();
  await expect(page.getByText(/Your account has limited access/)).toBeVisible();
  await page.getByRole("link", { name: "Export my data", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Export my data", exact: true })).toBeVisible();
  const boards = await (await page.request.get(origin + "/api/v1/boards")).json();
  await page.goto(origin + `/boards/${boards[0].id}/questions/new`);
  await expect(page.getByText("Email verification is required to post questions.")).toBeVisible();
  await expect(page.getByLabel("Question title")).toHaveCount(0);
  const csrf = await (await page.request.get(origin + "/api/v1/auth/csrf")).json();
  const denied = await page.request.post(origin + "/api/v1/reports", { headers: { [csrf.headerName]: csrf.token }, data: { questionId: randomUUID(), reason: "Limited browser check" } });
  expect(denied.status()).toBe(403);
  expect((await denied.json()).code).toBe("EMAIL_VERIFICATION_REQUIRED");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("pending-limited-mobile.png"), fullPage: true });
  sql(`UPDATE app_user SET account_state='ACTIVE',email_verified_at=clock_timestamp() WHERE id='${id}'`);
  expect((await page.request.get(origin + "/api/v1/auth/me")).status()).toBe(401);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page.getByText("Pending browser member", { exact: true })).toHaveCount(0);
  await login();
  await expect(page.getByText(/Your account has limited access/)).toHaveCount(0);
  await page.goto(origin + `/boards/${boards[0].id}/questions/new`);
  await expect(page.getByLabel("Question title")).toBeVisible();
});
