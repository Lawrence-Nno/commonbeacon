import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { inflateRawSync } from "node:zlib";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const source = "http://127.0.0.1:4173", target = "http://127.0.0.1:4176";
async function login(page: Page, origin: string, email: string) {
  await page.goto(origin + "/login");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}
async function password(page: Page) {
  await expect(page.getByLabel("Current password")).toBeFocused();
  await page.getByLabel("Current password").fill(process.env.DEMO_PASSWORD!);
  await page.getByLabel("Current password").press("Enter");
}
async function acknowledge(page: Page) {
  for (const box of await page.getByRole("checkbox").all()) { await box.focus(); await page.keyboard.press("Space"); }
  await page.getByRole("button", { name: "Confirm reviewed import" }).click();
}
function sql(statement: string) {
  const project = process.env.COMMONBEACON_E2E_PROJECT!;
  if (!/^commonbeacon-e2e-[a-z0-9-]+$/.test(project)) throw new Error("Disposable project required");
  const result = spawnSync("docker", ["compose", "--project-name", project + "-imports", "--env-file", ".env.example", "-f", "compose.e2e.yaml", "-f", "compose.import-e2e.yaml", "exec", "-T", "db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "e2e", "-d", "commonbeacon_e2e", "-Atc", statement], { cwd: fileURLToPath(new URL("../../", import.meta.url)), encoding: "utf8", timeout: 15000 });
  expect(result.status, result.stderr).toBe(0); return result.stdout.trim();
}
function unzip(bytes: Buffer) {
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06])), files = new Map<string, Buffer>();
  expect(end).toBeGreaterThan(0); let offset = bytes.readUInt32LE(end + 16);
  for (let i = 0; i < bytes.readUInt16LE(end + 10); i++) {
    const method = bytes.readUInt16LE(offset + 10), size = bytes.readUInt32LE(offset + 20), nameSize = bytes.readUInt16LE(offset + 28);
    const name = bytes.subarray(offset + 46, offset + 46 + nameSize).toString("utf8"), local = bytes.readUInt32LE(offset + 42);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28), data = bytes.subarray(start, start + size);
    files.set(name, method === 8 ? inflateRawSync(data) : data);
    offset += 46 + nameSize + bytes.readUInt16LE(offset + 30) + bytes.readUInt16LE(offset + 32);
  }
  return files;
}

test("browser exports A, resumes a private upload to fresh B, rejects stale review and reconciles a disconnected atomic commit", async ({ browser }, info) => {
  test.setTimeout(240000);
  const contextA = await browser.newContext(), contextB = await browser.newContext();
  const a = await contextA.newPage(), b = await contextB.newPage();
  try {
    await login(a, source, "avery.admin@example.test"); await a.goto(source + "/admin/data");
    await a.getByLabel("Include account contact details").check(); await a.getByLabel("Include private moderation history").check();
    await a.getByLabel(/I understand this archive/).check(); await a.getByRole("button", { name: "Confirm and create export" }).click(); await password(a);
    await expect(a.getByRole("button", { name: "Download archive" })).toBeVisible({ timeout: 30000 });
    await a.getByRole("button", { name: "Download archive" }).click(); const pending = a.waitForEvent("download"); await password(a);
    const download = await pending, archive = await readFile((await download.path())!), files = unzip(archive);
    const manifest = JSON.parse(files.get("manifest.json")!.toString());
    await login(b, target, "bootstrap@example.test"); await b.goto(target + "/admin/data");
    await b.getByRole("link", { name: "Import company data" }).click();
    await b.getByLabel("Archive file").setInputFiles({ name: "private-source.zip", mimeType: "application/zip", buffer: archive });
    let uploads = 0;
    await b.route("**/imports/*/archive", async route => { uploads++; if (uploads === 1) await route.abort("connectionreset"); else await route.continue(); });
    await b.getByRole("button", { name: "Confirm password and upload" }).click(); await password(b);
    await expect(b.getByRole("button", { name: "Check outcome" })).toBeVisible();
    await b.getByRole("button", { name: "Check outcome" }).click(); await b.getByRole("button", { name: "Upload complete file" }).click();
    await expect(b).toHaveURL(/\/imports\/[a-f0-9-]{36}$/); expect(uploads).toBe(2);
    const importUrl = b.url(), id = importUrl.split("/").at(-1)!;
    await b.reload(); await expect(b.getByText(/private-source.zip/)).toHaveCount(0);
    await expect(b.getByRole("button", { name: "Run dry run", exact: true })).toBeVisible({ timeout: 30000 });
    await b.getByRole("button", { name: "Run dry run", exact: true }).click();
    await expect(b.getByRole("heading", { name: "Review before activation" })).toBeVisible({ timeout: 30000 });
    await expect(b.getByText("No validation errors.")).toBeVisible();
    await b.setViewportSize({ width: 390, height: 844 });
    expect(await b.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await b.screenshot({ path: info.outputPath("import-review-mobile.png"), fullPage: true });
    await acknowledge(b);
    sql("UPDATE app_user SET display_name='Changed bootstrap' WHERE email='bootstrap@example.test'");
    await password(b); await expect(b.getByText(/No updated review will be submitted automatically/)).toBeVisible();
    await expect(b.getByRole("button", { name: "Confirm reviewed import" })).toBeDisabled();
    await b.getByRole("button", { name: "Run a fresh dry run" }).click();
    await expect(b.getByText("No validation errors.")).toBeVisible({ timeout: 30000 });
    await expect(b.getByRole("checkbox").first()).toBeEnabled({ timeout: 30000 });
    await acknowledge(b);
    let confirmations = 0;
    await b.route("**/imports/*/confirm", async route => { confirmations++; const response = await route.fetch(); expect(response.status()).toBe(202); await route.abort("connectionreset"); });
    await password(b); await expect(b.getByRole("button", { name: "Check outcome" })).toBeVisible();
    await b.getByRole("button", { name: "Check outcome" }).click();
    await expect(b.getByRole("heading", { name: "Import reconciliation" })).toBeVisible({ timeout: 40000 });
    expect(confirmations).toBe(1); await expect(b.getByRole("button", { name: "Cancel import" })).toHaveCount(0);
    const response = await b.request.get(target + `/api/v1/admin/data/imports/${id}/reconciliation`); expect(response.status()).toBe(200);
    const result = await response.json(); expect(result.state).toBe("COMPLETED");
    for (const file of manifest.files) expect(result.counts[file.name.replace(".jsonl", "")]).toEqual({ expected: file.count, created: file.count, skipped: 0, rejected: 0 });
    const mappings = new Map<string, string>(result.mappings.map((m: { sourceId: string; localId: string }) => [m.sourceId, m.localId]));
    const rows = (name: string) => files.get(name)!.toString().split("\n").filter(Boolean).map(line => JSON.parse(line));
    const actualQuestions = JSON.parse(sql("SELECT json_agg(row_to_json(q)) FROM (SELECT id,title,body,visibility FROM question) q"));
    for (const row of rows("questions.jsonl")) expect(actualQuestions.find((q: { id: string }) => q.id === mappings.get(row.id))).toMatchObject({ title: row.title, body: row.body, visibility: row.visibility });
    expect(Number(sql("SELECT count(*) FROM app_user WHERE account_state='IMPORTED_INACTIVE' AND role='MEMBER' AND password_hash IS NULL AND email IS NULL"))).toBe(result.counts.users.created);
    const publicContext = await browser.newContext();
    try {
      const hiddenReplies = rows("replies.jsonl").filter(q => q.visibility === "HIDDEN");
      const drafts = rows("articles.jsonl").filter(q => q.status !== "PUBLISHED");
      expect(hiddenReplies.length).toBeGreaterThan(0); expect(drafts.length).toBeGreaterThan(0);
      for (const row of hiddenReplies) expect((await publicContext.request.get(target + `/api/v1/replies/${mappings.get(row.id)}`)).status()).toBe(404);
      for (const row of rows("questions.jsonl").filter(q => q.visibility === "HIDDEN")) expect((await publicContext.request.get(target + `/api/v1/questions/${mappings.get(row.id)}`)).status()).toBe(404);
      for (const row of drafts) expect((await publicContext.request.get(target + `/api/v1/articles/${row.slug}`)).status()).toBe(404);
    } finally { await publicContext.close(); }
    await b.reload(); await expect(b.getByRole("heading", { name: "Import reconciliation" })).toBeVisible();
    const reportPending = b.waitForEvent("download"); await b.getByRole("button", { name: "Download reconciliation JSON (this mapping page)" }).click();
    expect(JSON.parse(await readFile((await (await reportPending).path())!, "utf8")).jobId).toBe(id);
    await b.getByRole("button", { name: "Sign out" }).click(); await expect(b.getByText("Sign in to import data.")).toBeVisible();
    await expect(b.getByText(`Reference: ${id}`)).toHaveCount(0);
  } finally { await contextA.close(); await contextB.close(); }
});

test("invalid archive shows bounded inspection errors and can be cancelled", async ({ page }) => {
  test.setTimeout(60000); await login(page, source, "avery.admin@example.test"); await page.goto(source + "/admin/data/imports");
  await page.getByLabel("Archive file").setInputFiles({ name: "broken.zip", mimeType: "application/zip", buffer: Buffer.from("not a zip") });
  await page.getByRole("button", { name: "Confirm password and upload" }).click(); await password(page);
  await expect(page).toHaveURL(/\/imports\/[a-f0-9-]{36}$/);
  await expect(page.getByText(/Archive inspection failed/)).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: "Cancel import" }).click();
  await expect(page.getByRole("heading", { name: "Import reconciliation" })).toBeVisible();
  await expect(page.getByText(/Detailed review data has expired or was never produced/)).toBeVisible();
});
