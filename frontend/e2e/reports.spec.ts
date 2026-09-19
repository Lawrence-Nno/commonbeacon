import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("members report archived questions and replies without publishing private reasons", async ({ browser }, testInfo) => {
  test.setTimeout(150_000);
  const origin = "http://127.0.0.1:4173";
  const member = await browser.newContext();
  const admin = await browser.newContext();
  const visitor = await browser.newContext();
  async function login(page: Page, email: string) {
    await page.goto(origin + "/login");
    for (let attempt = 0; attempt < 2; attempt++) {
      await page.getByLabel("Email address").fill(email);
      await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
      const result = page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/login") && response.request().method() === "POST");
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      const response = await result;
      if (response.status() !== 429 || attempt === 1) {
        expect(response.status()).toBe(200);
        break;
      }
      // Nginx shares an address across actors. Respect the real limiter, never disable it.
      const seconds = Number(response.headers()["retry-after"]);
      expect(Number.isFinite(seconds) && seconds > 0 && seconds <= 60).toBe(true);
      await page.waitForTimeout(seconds * 1000);
    }
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  }
  async function mutation(page: Page, path: string, data: unknown, method = "POST") {
    const csrf = await (await page.request.get(origin + "/api/v1/auth/csrf")).json();
    return page.request.fetch(origin + path, { method, data, headers: { [csrf.headerName]: csrf.token } });
  }
  try {
    const page = await member.newPage(); const adminPage = await admin.newPage();
    await login(page, "alex.member@example.test");
    await login(adminPage, "avery.admin@example.test");
    const boardResponse = await mutation(adminPage, "/api/v1/boards", {
      name: "Report test", slug: "report-" + randomUUID(), description: "Fictional reporting test board.",
    });
    expect(boardResponse.status()).toBe(201);
    const board = await boardResponse.json();
    const questionResponse = await mutation(page, `/api/v1/boards/${board.id}/questions`, {
      title: "Can someone help with setup?", body: "I have a fictional setup question.",
    });
    expect(questionResponse.status()).toBe(201);
    const question = await questionResponse.json();
    const replyResponse = await mutation(adminPage, `/api/v1/questions/${question.id}/replies`, { body: "Fictional answer." });
    expect(replyResponse.status()).toBe(201);
    const reply = await replyResponse.json();
    expect((await mutation(adminPage, `/api/v1/boards/${board.id}`, { archived: true, expectedVersion: 0 }, "PATCH")).status()).toBe(200);
    await page.goto(origin + "/questions/" + question.id);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("button", { name: "Report question", exact: true }).click();
    await page.getByLabel("Reason for reporting").fill("Private fictional question concern");
    await page.screenshot({ path: testInfo.outputPath("report-mobile.png"), fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.getByLabel("Reason for reporting").press("Tab");
    await expect(page.getByRole("button", { name: "Submit report" })).toBeFocused();
    await page.getByRole("button", { name: "Submit report" }).press("Enter");
    await expect(page.getByText("Your report was submitted.")).toBeVisible();
    await page.reload();
    await page.getByRole("button", { name: "Report question", exact: true }).click();
    await page.getByLabel("Reason for reporting").fill("Preserve my duplicate draft");
    await page.getByRole("button", { name: "Submit report" }).click();
    await expect(page.getByRole("alert")).toContainText("already have an open report");
    await expect(page.getByLabel("Reason for reporting")).toHaveValue("Preserve my duplicate draft");
    await page.getByRole("button", { name: "Cancel report" }).click();
    await page.getByRole("button", { name: "Report reply", exact: true }).click();
    await page.getByLabel("Reason for reporting").fill("Private fictional reply concern");
    await page.getByRole("button", { name: "Submit report" }).click();
    await expect(page.getByText("Your report was submitted.")).toBeVisible();
    const publicPage = await visitor.newPage();
    await publicPage.goto(page.url());
    await expect(publicPage.getByRole("button", { name: /Report (question|reply)/ })).toHaveCount(0);
    for (const path of [`/api/v1/questions/${question.id}`, `/api/v1/questions/${question.id}/replies`, `/api/v1/replies/${reply.id}`]) {
      const response = await publicPage.request.get(origin + path);
      expect(response.status()).toBe(200);
      expect(await response.text()).not.toMatch(/Private fictional|reporterId|resolutionNote/);
    }
    // A failed report does not remove content or turn a member into a moderator.
    expect((await page.request.get(origin + "/api/v1/moderation/reports")).status()).toBe(403);
  } finally {
    await Promise.all([member.close(), admin.close(), visitor.close()]);
  }
});
