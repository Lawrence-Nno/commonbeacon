import { expect, test, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const origin = "http://127.0.0.1:4173";
async function login(page: Page, email: string) {
  await page.goto(origin + "/login");await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}
async function confirm(page: Page, phrase: string) {
  await expect(page.getByRole("button", { name: "Confirm password and erase" })).toBeDisabled();
  for (const box of await page.getByRole("checkbox").all()) { await box.focus();await page.keyboard.press("Space"); }
  await page.getByLabel(/Type exactly/).fill(phrase);await page.getByRole("button", { name: "Confirm password and erase" }).click();
  await page.getByLabel("Current password").fill(process.env.DEMO_PASSWORD!);await page.getByRole("button", { name: "Confirm password", exact: true }).click();
}
test("account erasure survives receipt reload; company erasure survives restart without demo reseeding", async ({ page }, info) => {
  test.setTimeout(180000);
  await login(page, "alex.member@example.test");await page.goto(origin + "/account/delete");
  await page.getByRole("button", { name: "Review erasure impact" }).click();await expect(page.getByRole("heading", { name: "Review before erasure" })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath("account-erasure-mobile.png"), fullPage: true });
  await confirm(page, "DELETE MY ACCOUNT");await expect(page.getByRole("heading", { name: "Erasure progress" })).toBeVisible();
  const download = page.waitForEvent("download");await page.getByRole("button", { name: "Save private status receipt" }).click();
  const receipt = JSON.parse(await readFile((await (await download).path())!, "utf8"));
  expect((await page.request.get(origin + "/api/v1/boards")).status()).toBe(503);
  await page.goto(origin + "/erasure/receipt");await page.getByLabel("Receipt ID").fill(receipt.id);await page.getByLabel("Receipt token").fill(receipt.token);await page.getByRole("button", { name: "Check erasure status" }).click();
  await expect(page.getByText("Erasure completed for the disclosed scope.")).toBeVisible({ timeout: 70000 });
  await page.goto(origin + "/");await expect(page.getByRole("heading", { name: "Getting started", exact: true })).toBeVisible();
  await login(page, "avery.admin@example.test");await page.goto(origin + "/admin/erase");
  const response = page.waitForResponse(r => r.url().endsWith("/erasure/previews"));await page.getByRole("button", { name: "Review erasure impact" }).click();const preview = await (await response).json();
  await confirm(page, preview.phrase);await expect(page.getByRole("heading", { name: "Erasure progress" })).toBeVisible();
  // Restart only this runner-created, tmpfs-backed stack while the durable job is active.
  const project = process.env.COMMONBEACON_E2E_PROJECT!;expect(project).toMatch(/^commonbeacon-e2e-[a-z0-9-]+$/);
  const result = spawnSync("docker", ["compose", "--project-name", project, "--env-file", ".env.example", "-f", "compose.e2e.yaml", "-f", "compose.erasure-e2e.yaml", "restart", "backend"], { cwd: fileURLToPath(new URL("../../", import.meta.url)), encoding: "utf8", timeout: 60000 });
  expect(result.status, result.stderr).toBe(0);
  await expect(page.getByText("Erasure completed for the disclosed scope.")).toBeVisible({ timeout: 80000 });
  const boards = await page.request.get(origin + "/api/v1/boards");expect(boards.ok()).toBe(true);expect(await boards.json()).toEqual([]);
  await login(page, "avery.admin@example.test");await page.goto(origin + "/admin/erase");
  const after = page.waitForResponse(r => r.url().endsWith("/erasure/previews"));await page.getByRole("button", { name: "Review erasure impact" }).click();const empty = await (await after).json();
  expect(empty.counts.app_user).toBe(1);expect(empty.counts.question).toBe(0);expect(empty.counts.transfer_artifact).toBe(0);
});
