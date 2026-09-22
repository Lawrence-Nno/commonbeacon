import { expect, test } from "@playwright/test";
import { readFile, readdir } from "node:fs/promises";
import { request as httpRequest } from "node:http";
import { createHash } from "node:crypto";

const origin = "http://127.0.0.1:4173";
function crc32(bytes: Buffer) {
  let crc = 0xffffffff;
  for (const b of bytes) { crc ^= b; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
async function fixtureArchive() {
  const root = new URL("../../backend/src/test/resources/data-transfer/v1/company-empty/", import.meta.url);
  const local: Buffer[] = [], directory: Buffer[] = []; let offset = 0;
  for (const name of (await readdir(root)).sort()) {
    const data = await readFile(new URL(name, root)), filename = Buffer.from(name), crc = crc32(data);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50); h.writeUInt16LE(20, 4); h.writeUInt32LE(crc, 14);
    h.writeUInt32LE(data.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(filename.length, 26);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6);
    c.writeUInt32LE(crc, 16); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(filename.length, 28); c.writeUInt32LE(offset, 42);
    local.push(h, filename, data); directory.push(c, filename); offset += h.length + filename.length + data.length;
  }
  const d = Buffer.concat(directory), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(directory.length / 2, 8); end.writeUInt16LE(directory.length / 2, 10); end.writeUInt32LE(d.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, d, end]);
}
async function rejectedByProxy(path: string, size: number) {
  return new Promise<number>((resolve, reject) => {
    const req = httpRequest(origin + path, { method: "PUT", headers: { "Content-Length": size, Expect: "100-continue", "Content-Type": "application/zip" } }, res => { res.resume(); resolve(res.statusCode!); });
    req.on("error", reject); req.setTimeout(10000, () => req.destroy(new Error("Proxy response timed out"))); req.flushHeaders();
  });
}
test("quarantines native uploads through the proxy without activating domain data", async ({ page }) => {
  test.setTimeout(150000);
  await page.goto(origin + "/login");
  await page.getByLabel("Email address").fill("avery.admin@example.test");
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  const csrf = await (await page.request.get(origin + "/api/v1/auth/csrf")).json();
  const headers = { [csrf.headerName]: csrf.token };
  const before = await (await page.request.get(origin + "/api/v1/boards")).json();
  const grant = await page.request.post(origin + "/api/v1/account/data/reauthentication", { headers, data: { password: process.env.DEMO_PASSWORD, scope: "IMPORT_UPLOAD" } });
  expect(grant.status()).toBe(200);
  const created = await page.request.post(origin + "/api/v1/admin/data/imports", { headers: { ...headers, "Idempotency-Key": crypto.randomUUID() }, data: { formatVersion: 1, recentAuthGrant: (await grant.json()).token } });
  expect(created.status()).toBe(201); const job = await created.json(), path = `/api/v1/admin/data/imports/${job.id}`;
  expect(await rejectedByProxy(path + "/archive", 67108865)).toBe(413);
  expect(await rejectedByProxy("/api/v1/boards", 1048577)).toBe(413);
  // A body over the ordinary 1 MiB API ceiling reaches application ownership checks only on this route.
  const unknown = await page.request.put(origin + `/api/v1/admin/data/imports/${crypto.randomUUID()}/archive`, { headers: { ...headers, "Content-Type": "application/zip" }, data: Buffer.alloc(1048577) });
  expect(unknown.status()).toBe(404);
  const empty = await page.request.put(origin + path + "/archive", { headers: { ...headers, "Content-Type": "application/zip" }, data: Buffer.alloc(0) }); expect(empty.status()).toBe(400);
  const archive = await fixtureArchive();
  const uploaded = await page.request.put(origin + path + "/archive", { headers: { ...headers, "Content-Type": "application/zip" }, data: archive });
  expect(uploaded.status()).toBe(200); expect((await uploaded.json()).state).toBe("UPLOADED");
  await expect.poll(async () => (await page.request.get(origin + path + "/inspection")).status(), { timeout: 30000 }).toBe(200);
  const report = await (await page.request.get(origin + path + "/inspection")).json();
  expect(report.valid).toBe(true); expect(report.activationAvailable).toBe(false); expect(report.issues).toEqual([]);
  expect(report.archiveSha256).toBe(createHash("sha256").update(archive).digest("hex"));
  expect(await (await page.request.get(origin + "/api/v1/boards")).json()).toEqual(before);
  const status = await (await page.request.get(origin + `/api/v1/admin/data/jobs/${job.id}`)).json();
  expect(status.state).toBe("REVIEW_REQUIRED"); expect(status.allowedActions).not.toContain("DOWNLOAD");
  const cancelled = await page.request.post(origin + `/api/v1/admin/data/jobs/${job.id}/cancel`, { headers: { ...headers, "Idempotency-Key": crypto.randomUUID() }, data: { expectedVersion: status.version } }); expect(cancelled.status()).toBe(200);
});
