import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { inflateRawSync } from "node:zlib";
import { createHash } from "node:crypto";

const origin = "http://127.0.0.1:4173";

test("members download only their personal portability profile", async ({ page }, testInfo) => {
  test.setTimeout(150_000);
  await login(page, "alex.member@example.test");
  const actor = await (await page.request.get(origin + "/api/v1/auth/me")).json();
  await page.getByRole("link", { name: "Export my data", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Export my data", exact: true })).toBeVisible();
  await expect(page.getByLabel("Include private moderation history")).toHaveCount(0);
  await page.getByLabel(/I understand this archive/).check();
  await page.getByRole("button", { name: "Confirm and create export" }).click(); await confirm(page);
  const card = page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: "Personal export — Ready to download" }) });
  await expect(card).toBeVisible({ timeout: 30_000 });
  await page.getByRole("link", { name: "View export history" }).click();
  await expect(page).toHaveURL(/\/account\/data\/history$/);
  await expect(page.getByRole("heading", { name: "Export history", exact: true })).toBeVisible();
  await expect(page.getByText(/Reference:/)).toHaveCount(0);
  await page.getByRole("button", { name: /View details/ }).first().click();
  await card.getByRole("button", { name: "Download archive" }).click();
  const pending = page.waitForEvent("download"); await confirm(page);
  const download = await pending;
  const files = unzip(await readFile((await download.path())!));
  const rows = (name: string) => files.get(name)!.toString("utf8").split("\n").filter(Boolean).map(line => JSON.parse(line));
  const manifest = JSON.parse(files.get("manifest.json")!.toString("utf8"));
  expect(manifest.profile).toBe("personal");expect(manifest.referencePolicy).toBe("opaque-personal-context");
  expect(rows("users.jsonl")).toHaveLength(1);expect(rows("users.jsonl")[0].id).toBe(actor.id);
  expect(files.has("contacts.jsonl")).toBe(false);expect(files.has("actions.jsonl")).toBe(false);
  for (const name of ["questions.jsonl", "replies.jsonl", "articles.jsonl"]) for (const row of rows(name)) expect(row.authorId).toBe(actor.id);
  for (const row of rows("reports.jsonl")) expect(Object.keys(row).sort()).toEqual(["createdAt", "id", "questionId", "reason", "replyId"]);
  for (const file of manifest.files) expect(createHash("sha256").update(files.get(file.name)!).digest("hex")).toBe(file.sha256);
  await expect(card.getByText(/Archive sent to your browser downloads/)).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("personal-export-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Sign in to manage data." })).toBeVisible();
  await expect(page.getByText(/Reference:/)).toHaveCount(0);
});
async function login(page: Page, email: string) {
  await page.goto(origin + "/login");
  for (let attempt = 0; attempt < 2; attempt++) {
    await page.getByLabel("Email address").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
    const pending = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/login") && r.request().method() === "POST");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    const response = await pending;
    if (response.status() !== 429 || attempt === 1) { expect(response.status()).toBe(200); break; }
    const delay = Number(response.headers()["retry-after"]);
    expect(delay > 0 && delay <= 60).toBe(true); await page.waitForTimeout(delay * 1000);
  }
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}
async function confirm(page: Page) {
  await expect(page.getByLabel("Current password")).toBeFocused();
  await page.getByLabel("Current password").fill(process.env.DEMO_PASSWORD!);
  await page.getByLabel("Current password").press("Enter");
}
// Read the ZIP central directory, so data descriptors do not affect size lookup.
function unzip(bytes: Buffer) {
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  expect(end).toBeGreaterThan(0);
  const files = new Map<string, Buffer>();
  let offset = bytes.readUInt32LE(end + 16);
  for (let i = 0; i < bytes.readUInt16LE(end + 10); i++) {
    expect(bytes.readUInt32LE(offset)).toBe(0x02014b50);
    const method = bytes.readUInt16LE(offset + 10), size = bytes.readUInt32LE(offset + 20);
    const nameSize = bytes.readUInt16LE(offset + 28), extra = bytes.readUInt16LE(offset + 30), comment = bytes.readUInt16LE(offset + 32);
    const name = bytes.subarray(offset + 46, offset + 46 + nameSize).toString("utf8");
    const local = bytes.readUInt32LE(offset + 42);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const compressed = bytes.subarray(start, start + size);
    expect([0, 8]).toContain(method);
    files.set(name, method === 8 ? inflateRawSync(compressed) : compressed);
    offset += 46 + nameSize + extra + comment;
  }
  return files;
}

test("administrator retries safely, downloads a real archive, and clears private state on account changes", async ({ page }, testInfo) => {
  test.setTimeout(210_000);
  const browserErrors: string[] = []; page.on("pageerror", error => browserErrors.push(error.message));
  await login(page, "avery.admin@example.test");
  await page.getByRole("link", { name: "Data management", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Data management", exact: true })).toBeVisible();
  await expect(page.getByLabel("Include account contact details")).not.toBeChecked();
  await expect(page.getByLabel("Include private moderation history")).not.toBeChecked();
  await expect(page.getByRole("button", { name: "Confirm and create export" })).toBeDisabled();
  await page.getByLabel("Include account contact details").focus(); await page.keyboard.press("Space");
  await page.getByLabel("Include private moderation history").check();
  await expect(page.getByText("Contact details: included.")).toBeVisible();
  await page.getByLabel(/I understand this archive/).check();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({ path: testInfo.outputPath("data-management-desktop.png"), fullPage: true });
  const keys: string[] = []; let attempts = 0; let jobId = "";
  await page.route("**/api/v1/admin/data/exports", async route => {
    attempts++; keys.push(route.request().headers()["idempotency-key"]);
    if (attempts === 1) {
      await route.fulfill({ status: 403, contentType: "application/problem+json", body: JSON.stringify({ code: "RECENT_AUTH_REQUIRED", detail: "Confirmation expired." }) });
    } else if (attempts === 2) {
      const response = await route.fetch(); expect(response.status()).toBe(202); jobId = (await response.json()).id;
      await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ code: "TRANSFER_UNAVAILABLE", detail: "Simulated lost success response. Retry the same request." }) });
    } else await route.continue();
  });
  await page.getByRole("button", { name: "Confirm and create export" }).click(); await confirm(page);
  await expect(page.getByRole("alert")).toContainText("Password confirmation expired");
  await page.getByRole("button", { name: "Retry export request" }).click(); await confirm(page);
  await expect(page.getByRole("alert")).toContainText("Simulated lost success response");
  await expect(page.getByLabel("Include account contact details")).toBeChecked();
  await page.getByRole("button", { name: "Retry export request" }).click(); await confirm(page);
  await expect(page.getByText("Export requested. Its status appears under Latest export below.")).toBeVisible();
  expect(new Set(keys).size).toBe(1);
  const item = page.getByRole("listitem").filter({ hasText: `Reference: ${jobId}` });
  await expect(item.getByRole("heading", { name: "Company export — Ready to download" })).toBeVisible({ timeout: 30_000 });
  const history = await (await page.request.get(origin + "/api/v1/admin/data/jobs")).json();
  expect(history.items.filter((j: { id: string }) => j.id === jobId)).toHaveLength(1);
  await item.getByRole("button", { name: "Download archive" }).click();
  await expect(item.getByLabel("Current password")).toBeVisible();
  await expect(item.getByText(/Confirm your password below to start downloading/)).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("download-confirmation.png"), fullPage: true });
  const pendingDownload = page.waitForEvent("download"); await confirm(page);
  const download = await pendingDownload;
  expect(download.suggestedFilename()).toBe(`commonbeacon-${jobId}.zip`);
  const downloadedPath = await download.path(); expect(downloadedPath).not.toBeNull();
  const entries = unzip(await readFile(downloadedPath!));
  const manifest = JSON.parse(entries.get("manifest.json")!.toString("utf8"));
  expect(manifest.formatVersion).toBe(1); expect(manifest.profile).toBe("company");
  expect(manifest.options).toEqual({ includeContacts: true, includeModerationHistory: true });
  expect(entries.has("contacts.jsonl")).toBe(true); expect(entries.has("reports.jsonl")).toBe(true);
  for (const file of manifest.files) {
    const bytes = entries.get(file.name)!;
    expect(bytes.length).toBe(file.uncompressedBytes);
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(file.sha256);
  }
  expect(entries.get("users.jsonl")!.toString()).not.toContain("password_hash");
  await expect(item.getByText(/Archive sent to your browser downloads/)).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("heading", { name: "Data management", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("data-management-mobile.png"), fullPage: true });
  await page.getByRole("link", { name: "View export history" }).click();
  await expect(page).toHaveURL(/\/admin\/data\/history$/);
  await expect(page.getByText(/Reference:/)).toHaveCount(0);
  await page.getByRole("button", { name: /View details/ }).first().focus();
  await page.keyboard.press("Enter");
  await expect(item).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("export-history-mobile.png"), fullPage: true });
  await item.getByRole("button", { name: "Download archive" }).click();
  await page.getByLabel("Current password").fill("unsent-private-value");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Sign in to manage data." })).toBeVisible();
  await expect(page.getByLabel("Current password")).toHaveCount(0);
  await expect(page.getByText(`Reference: ${jobId}`)).toHaveCount(0);
  await login(page, "alex.member@example.test");
  await expect(page.getByRole("link", { name: "Data management", exact: true })).toHaveCount(0);
  await page.goto(origin + "/admin/data");
  await expect(page.getByRole("heading", { name: "Data management is restricted to administrators." })).toBeVisible();
  expect((await page.request.get(origin + "/api/v1/admin/data/jobs")).status()).toBe(403);
  expect(browserErrors).toEqual([]);
});
