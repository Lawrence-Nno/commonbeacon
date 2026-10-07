import { expect, test } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const origin = "http://127.0.0.1:4173";
const token = "a".repeat(43);

test("all email deep links scrub URL secrets, prohibit passive mutations, and require explicit submit", async ({ page }, testInfo) => {
  const requests: string[] = [];
  page.on("request", request => requests.push(request.url()));
  for (const route of ["verify-email", "reset-password", "confirm-email-change"]) {
    const response = await page.goto(`${origin}/${route}#token=${token}`);
    expect(response?.status()).toBe(200);
    expect(response?.headers()["cache-control"]).toBe("no-store");
    expect(response?.headers()["referrer-policy"]).toBe("no-referrer");
    expect(response?.headers()["content-security-policy"]).toContain("default-src 'self'");
    await expect(page).toHaveURL(`${origin}/${route}`);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => ({ state: history.state, stored: Object.values(localStorage) }))).toEqual({ state: null, stored: [] });
    expect(requests.some(url => url.includes(token) || url.includes("/api/"))).toBe(false);
    expect(requests.every(url => new URL(url).origin === origin)).toBe(true);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(route + "-mobile.png"), fullPage: true });
    if (route === "reset-password") {
      await page.getByLabel("New password", { exact: true }).fill("test-password-123");
      await page.getByLabel("Confirm new password").fill("test-password-123");
    }
    await page.getByRole("button").click();
    await expect(page.getByRole("alert")).toContainText("could not process");
    expect(requests.some(url => url.includes("/api/"))).toBe(false);
    // Reload intentionally loses the in-memory token; reopen the original email to retry.
    await page.reload();
    await expect(page.getByRole("alert")).toContainText("Open the full link");
    await expect(page.getByRole("button")).toHaveCount(0);
    expect((await page.request.head(`${origin}/${route}`)).status()).toBe(200);
  }
});

test("malformed and query links are scrubbed and cannot redirect or submit", async ({ page }) => {
  for (const suffix of [`?token=${token}`, `#token=${token}&next=https://evil.test`, "#token=bad"]) {
    await page.goto(origin + "/verify-email" + suffix);
    await expect(page).toHaveURL(origin + "/verify-email");
    await expect(page.getByRole("alert")).toContainText("incomplete");
    await expect(page.getByRole("button")).toHaveCount(0);
  }
  const project = process.env.COMMONBEACON_E2E_PROJECT ?? "";
  if (!/^commonbeacon-e2e-[a-z0-9-]+$/.test(project)) throw new Error("Disposable project required");
  const logs = spawnSync("docker", ["compose", "--project-name", project, "--env-file", ".env.example", "-f", "compose.e2e.yaml", "logs", "--no-color", "frontend"],
    { cwd: fileURLToPath(new URL("../../", import.meta.url)), encoding: "utf8", timeout: 15000 });
  expect(logs.status).toBe(0);
  expect(logs.stdout + logs.stderr).not.toContain(token);
});

test("Mailpit UI belongs to the separate temporary email fixture", async ({ page }) => {
  const response = await page.goto("http://127.0.0.1:8027");
  expect(response?.status()).toBe(200);
  await expect(page).toHaveTitle(/Mailpit/);
  const messages = await (await page.request.get("http://127.0.0.1:8027/api/v1/messages")).json();
  expect(messages.total).toBe(0);
});
