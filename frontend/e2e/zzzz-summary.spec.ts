import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("operators see exact overview changes and private cards disappear on sign out", async ({ browser }, testInfo) => {
  test.setTimeout(180_000);
  const origin = "http://127.0.0.1:4173";
  const admin = await browser.newContext(), moderator = await browser.newContext(), visitor = await browser.newContext();
  async function login(page: Page, email: string) {
    await page.goto(origin + "/login");
    for (let attempt = 0; attempt < 2; attempt++) {
      await page.getByLabel("Email address").fill(email);
      await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
      const pending = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/login") && r.request().method() === "POST");
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      const response = await pending;
      if (response.status() !== 429 || attempt === 1) { expect(response.status()).toBe(200); break; }
      const seconds = Number(response.headers()["retry-after"]);
      expect(Number.isFinite(seconds) && seconds > 0 && seconds <= 60).toBe(true);
      await page.waitForTimeout(seconds * 1000);
    }
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  }
  try {
    const edit = await admin.newPage(), mod = await moderator.newPage();
    await login(edit, "avery.admin@example.test");
    await login(mod, "morgan.moderator@example.test");
    async function mutation(page: Page, path: string, data: unknown, status = 200, method = "POST") {
      const csrf = await (await page.request.get(origin + "/api/v1/auth/csrf")).json();
      const response = await page.request.fetch(origin + path, { method, data, headers: { [csrf.headerName]: csrf.token } });
      expect(response.status(), await response.text()).toBe(status);
      return response.json();
    }
    const summaryPath = origin + "/api/v1/moderation/summary";
    expect((await visitor.request.get(summaryPath)).status()).toBe(401);
    const baseline = await (await edit.request.get(summaryPath)).json();
    const board = await mutation(edit, "/api/v1/boards", { slug: "overview-" + randomUUID(), name: "Overview test", description: "Fictional overview fixtures." }, 201);
    const question = await mutation(edit, `/api/v1/boards/${board.id}/questions`, { title: "Fictional overview question", body: "A fictional question for operational counts." }, 201);
    const reply = await mutation(mod, `/api/v1/questions/${question.id}/replies`, { body: "A fictional accepted answer for overview counts." }, 201);
    await mutation(edit, `/api/v1/questions/${question.id}/accepted-reply`, { replyId: reply.id, expectedVersion: question.version }, 200, "PUT");
    const draft = await mutation(edit, "/api/v1/admin/articles", { slug: "overview-" + randomUUID(), title: "Fictional overview article", body: "A fictional article for operational counts." }, 201);
    const published = await mutation(edit, `/api/v1/admin/articles/${draft.id}/publish`, { expectedVersion: draft.version });
    await mutation(edit, `/api/v1/boards/${board.id}`, { archived: true, expectedVersion: board.version }, 200, "PATCH");
    // Reports are permitted on archived boards and count separately per reporter.
    const report = await mutation(edit, "/api/v1/reports", { replyId: reply.id, reason: "Fictional overview concern" }, 201);
    await mutation(mod, "/api/v1/reports", { replyId: reply.id, reason: "Another fictional overview concern" }, 201);
    async function overview(page: Page, unanswered: number, reports: number, articles: number) {
      await page.goto(origin + "/moderation");
      const cards = page.getByRole("region", { name: "Operational summary" }).locator("dd");
      await expect(cards).toHaveText([String(baseline.unansweredQuestions + unanswered), String(baseline.openReports + reports), String(baseline.publishedArticles + articles)]);
    }
    await overview(edit, 0, 2, 1);
    await overview(mod, 0, 2, 1);
    await mod.screenshot({ path: testInfo.outputPath("overview-desktop.png"), fullPage: true });
    const review = await (await edit.request.get(origin + `/api/v1/moderation/reports/${report.id}`)).json();
    await mutation(edit, `/api/v1/moderation/reports/${report.id}/resolve`, { decision: "HIDE", resolutionNote: "Hide fictional accepted reply", expectedVersion: review.report.version, expectedTargetVersion: review.context.reply.version, expectedQuestionVersion: review.context.question.version });
    await overview(mod, 1, 1, 1);
    const context = await (await edit.request.get(origin + `/api/v1/moderation/replies/${reply.id}`)).json();
    await mutation(edit, `/api/v1/moderation/replies/${reply.id}/restore`, { reason: "Restore fictional reply", expectedTargetVersion: context.reply.version, expectedQuestionVersion: context.question.version });
    await mutation(edit, `/api/v1/admin/articles/${published.id}/archive`, { expectedVersion: published.version });
    await overview(mod, 1, 1, 0);
    await mod.setViewportSize({ width: 390, height: 844 });
    const region = mod.getByRole("region", { name: "Operational summary" });
    await region.getByRole("link", { name: "Browse boards" }).focus();
    await mod.keyboard.press("Tab");
    await expect(region.getByRole("link", { name: "Review open reports" })).toBeFocused();
    expect(await mod.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await mod.screenshot({ path: testInfo.outputPath("overview-mobile.png"), fullPage: true });
    await region.getByRole("link", { name: "Review open reports" }).click();
    await expect(mod).toHaveURL(/status=OPEN&page=0/);
    await mod.getByRole("button", { name: "Sign out" }).click();
    await mod.goto(origin + "/moderation");
    await expect(mod.getByRole("heading", { name: "Sign in to review reports." })).toBeVisible();
    await expect(region).toHaveCount(0);
    await login(mod, "alex.member@example.test");
    expect((await mod.request.get(summaryPath)).status()).toBe(403);
    await mod.goto(origin + "/moderation");
    await expect(mod.getByRole("heading", { name: "Report review is restricted." })).toBeVisible();
    await expect(region).toHaveCount(0);
  } finally { await admin.close(); await moderator.close(); await visitor.close(); }
});
