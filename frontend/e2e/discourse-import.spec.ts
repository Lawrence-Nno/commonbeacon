import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("Discourse source bundle is reviewed and activated through the browser", async ({ page }, info) => {
  test.setTimeout(150000);
  const origin = "http://127.0.0.1:4176";
  await page.goto(origin + "/login");
  await page.getByLabel("Email address").fill("bootstrap@example.test");
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await page.goto(origin + "/admin/data/imports");
  await page.getByLabel("Archive format").selectOption("DISCOURSE");
  const bytes = await readFile(new URL("../../backend/src/test/resources/data-transfer/discourse-3.5.0/bundle.json", import.meta.url));
  const source = JSON.parse(bytes.toString());
  await page.getByLabel("Archive file").setInputFiles({ name: "discourse.json", mimeType: "application/json", buffer: bytes });
  await page.getByRole("button", { name: "Confirm password and upload" }).click();
  await page.getByLabel("Current password").fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Confirm password", exact: true }).click();
  await expect(page).toHaveURL(/\/imports\/[a-f0-9-]{36}$/);
  const id = page.url().split("/").at(-1)!;
  await page.reload();
  await expect(page.getByRole("button", { name: "Run dry run", exact: true })).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: "Run dry run", exact: true }).click();
  await expect(page.getByText("No validation errors.")).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole("checkbox", { name: /markup is preserved as literal text/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirm reviewed import" })).toBeDisabled();
  await page.getByText("Source-to-local mapping preview (up to 100)").click();
  await expect(page.getByText(new RegExp(`Discourse topic #${source.topics[0].id}\\)`))).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath("discourse-review-mobile.png"), fullPage: true });
  for (const box of await page.getByRole("checkbox").all()) { await box.focus(); await page.keyboard.press("Space"); }
  await page.getByRole("button", { name: "Confirm reviewed import" }).click();
  await page.getByLabel("Current password").fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Confirm password", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Import reconciliation" })).toBeVisible({ timeout: 40000 });
  const response = await page.request.get(origin + `/api/v1/admin/data/imports/${id}/reconciliation`);
  expect(response.ok()).toBe(true);const result = await response.json();expect(result.state).toBe("COMPLETED");
  for (const entity of ["users", "boards", "questions", "replies"])
    expect(result.counts[entity]).toEqual({ expected: 2, created: 2, skipped: 0, rejected: 0 });
  expect(result.mappings).toHaveLength(8);
  await page.reload();await expect(page.getByRole("heading", { name: "Import reconciliation" })).toBeVisible();
});
