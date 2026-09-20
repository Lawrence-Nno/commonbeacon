import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("members report archived questions and replies without publishing private reasons", async ({ browser }, testInfo) => {
  test.setTimeout(210_000);
  const origin = "http://127.0.0.1:4173";
  const member = await browser.newContext();
  const admin = await browser.newContext();
  const visitor = await browser.newContext();
  const moderator = await browser.newContext();
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
    const baseline = await (await adminPage.request.get(origin + "/api/v1/moderation/summary")).json();
    async function overview(actor: Page, unanswered: number, open: number) {
      await actor.goto(origin + "/moderation");
      await expect(actor.getByRole("region", { name: "Operational summary" }).locator("dd")).toHaveText([
        String(baseline.unansweredQuestions + unanswered), String(baseline.openReports + open), String(baseline.publishedArticles),
      ]);
    }
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
    expect((await mutation(page, `/api/v1/questions/${question.id}/accepted-reply`, { replyId: reply.id, expectedVersion: 0 }, "PUT")).status()).toBe(200);
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
    expect((await publicPage.request.get(origin + "/api/v1/moderation/reports")).status()).toBe(401);
    const queueResponse = await adminPage.request.get(origin + "/api/v1/moderation/reports");
    expect(queueResponse.status()).toBe(200);
    expect(queueResponse.headers()["cache-control"]).toBe("no-store");
    const queue = await queueResponse.json();
    const reportedReply = queue.items.find((item: { targetId: string }) => item.targetId === reply.id);
    expect(reportedReply).toBeTruthy();
    await adminPage.goto(origin + "/moderation");
    await expect(adminPage.getByText("Private fictional question concern")).toBeVisible();
    await adminPage.getByLabel("Report status").selectOption("RESOLVED");
    await expect(adminPage.getByText("Private fictional question concern")).toHaveCount(0);
    const reviewPage = await moderator.newPage();
    await login(reviewPage, "morgan.moderator@example.test");
    await reviewPage.getByRole("link", { name: "Report review", exact: true }).click();
    await expect(reviewPage.getByRole("heading", { name: "Report review", exact: true })).toBeVisible();
    await reviewPage.setViewportSize({ width: 390, height: 844 });
    await reviewPage.getByLabel("Report status").focus();
    await reviewPage.keyboard.press("Tab");
    await expect(reviewPage.getByRole("link", { name: "Review question report" }).first()).toBeFocused();
    await reviewPage.screenshot({ path: testInfo.outputPath("moderation-mobile.png"), fullPage: true });
    expect(await reviewPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await reviewPage.goto(origin + "/moderation/reports/" + reportedReply.id);
    await expect(reviewPage.getByRole("heading", { name: "Reported reply", exact: true })).toBeVisible();
    await expect(reviewPage.getByText("Private fictional reply concern")).toBeVisible();
    await expect(reviewPage.getByText("Board: Report test (archived)")).toBeVisible();
    await expect(reviewPage.getByRole("button", { name: "Resolve report", exact: true })).toBeDisabled();
    await reviewPage.reload();
    await expect(reviewPage.getByText("Private fictional reply concern")).toBeVisible();
    await reviewPage.setViewportSize({ width: 1280, height: 900 });
    await reviewPage.screenshot({ path: testInfo.outputPath("moderation-detail-desktop.png"), fullPage: true });
    const stale = await moderator.newPage();
    await stale.goto(reviewPage.url());
    await stale.getByLabel("Decision", { exact: true }).selectOption("HIDE");
    await stale.getByLabel("Resolution note").fill("Keep this stale review note");
    await overview(adminPage, 0, 2);
    await reviewPage.getByLabel("Decision", { exact: true }).selectOption("HIDE");
    await reviewPage.getByLabel("Resolution note").fill("Reviewed fictional unsafe reply");
    await reviewPage.setViewportSize({ width: 390, height: 844 });
    await reviewPage.screenshot({ path: testInfo.outputPath("resolution-mobile.png"), fullPage: true });
    expect(await reviewPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await reviewPage.getByLabel("Resolution note").press("Tab");
    await expect(reviewPage.getByRole("button", { name: "Resolve report", exact: true })).toBeFocused();
    await reviewPage.getByRole("button", { name: "Resolve report", exact: true }).press("Enter");
    await expect(reviewPage.getByText("Resolved report", { exact: true })).toBeVisible();
    await expect(reviewPage.getByText("Reviewed fictional unsafe reply")).toBeVisible();
    expect((await publicPage.request.get(origin + `/api/v1/replies/${reply.id}`)).status()).toBe(404);
    await stale.getByRole("button", { name: "Resolve report", exact: true }).click();
    await expect(stale.getByText(/Your note is preserved/)).toBeVisible();
    await expect(stale.getByLabel("Resolution note")).toHaveValue("Keep this stale review note");
    await expect(stale.getByRole("button", { name: "Resolve report", exact: true })).toBeDisabled();
    await stale.getByRole("button", { name: "Reload report context" }).click();
    await expect(stale.getByText("Resolved report", { exact: true })).toBeVisible();
    await stale.close();
    await overview(adminPage, 1, 1);
    const updatedQuestion = await (await publicPage.request.get(origin + `/api/v1/questions/${question.id}`)).json();
    expect(updatedQuestion.acceptedReply).toBeNull();
    expect(updatedQuestion.solved).toBe(false);
    const remainingQueue = await (await adminPage.request.get(origin + "/api/v1/moderation/reports")).json();
    expect(remainingQueue.items.some((item: { targetId: string }) => item.targetId === question.id)).toBe(true);
    expect(remainingQueue.items.some((item: { targetId: string }) => item.targetId === reply.id)).toBe(false);
    await reviewPage.reload();
    await expect(reviewPage.getByText("Resolved report", { exact: true })).toBeVisible();
    await reviewPage.setViewportSize({ width: 1280, height: 900 });
    await reviewPage.screenshot({ path: testInfo.outputPath("resolution-desktop.png"), fullPage: true });
    const reviewUrl = reviewPage.url();
    await reviewPage.getByRole("link", { name: "Reply history and restoration" }).click();
    await expect(reviewPage.getByRole("heading", { name: "Visibility history" })).toBeVisible();
    await expect(reviewPage.getByText("Hidden by Morgan Vale")).toBeVisible();
    await reviewPage.getByLabel("Restoration reason").fill("Reviewed fictional reply is safe to restore");
    await reviewPage.setViewportSize({ width: 390, height: 844 });
    await reviewPage.screenshot({ path: testInfo.outputPath("restoration-mobile.png"), fullPage: true });
    expect(await reviewPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await reviewPage.getByLabel("Restoration reason").press("Tab");
    await expect(reviewPage.getByRole("button", { name: "Restore content", exact: true })).toBeFocused();
    await reviewPage.getByRole("button", { name: "Restore content", exact: true }).press("Enter");
    await expect(reviewPage.getByText(/No restoration is needed/)).toBeVisible();
    await expect(reviewPage.getByText("Restored by Morgan Vale")).toBeVisible();
    await expect(reviewPage.getByText("Reviewed fictional reply is safe to restore")).toBeVisible();
    expect((await publicPage.request.get(origin + `/api/v1/replies/${reply.id}`)).status()).toBe(200);
    const restoredQuestion = await (await publicPage.request.get(origin + `/api/v1/questions/${question.id}`)).json();
    expect(restoredQuestion.acceptedReply).toBeNull();
    expect(restoredQuestion.solved).toBe(false);
    await overview(adminPage, 1, 1);
    await reviewPage.reload();
    await expect(reviewPage.getByText("Restored by Morgan Vale")).toBeVisible();
    await reviewPage.setViewportSize({ width: 1280, height: 900 });
    await reviewPage.screenshot({ path: testInfo.outputPath("restoration-history-desktop.png"), fullPage: true });
    await reviewPage.goto(origin + "/moderation");
    const reportedQuestion = queue.items.find((item: { targetId: string }) => item.targetId === question.id);
    await reviewPage.goto(origin + "/moderation/reports/" + reportedQuestion.id);
    await reviewPage.getByLabel("Decision", { exact: true }).selectOption("HIDE");
    await reviewPage.getByLabel("Resolution note").fill("Review parent question visibility separately");
    await reviewPage.getByRole("button", { name: "Resolve report", exact: true }).click();
    await expect(reviewPage.getByText("Resolved report", { exact: true })).toBeVisible();
    await overview(adminPage, 0, 0);
    await reviewPage.goto(origin + "/moderation/replies/" + reply.id);
    await expect(reviewPage.getByText(/hidden question keeps it private/)).toBeVisible();
    await expect(reviewPage.getByRole("button", { name: "Restore content", exact: true })).toHaveCount(0);
    expect((await publicPage.request.get(origin + `/api/v1/replies/${reply.id}`)).status()).toBe(404);
    await reviewPage.getByRole("link", { name: "Question history and restoration" }).click();
    await reviewPage.getByLabel("Restoration reason").fill("Parent reviewed and safe to show again");
    await reviewPage.getByRole("button", { name: "Restore content", exact: true }).click();
    await expect(reviewPage.getByText(/No restoration is needed/)).toBeVisible();
    expect((await publicPage.request.get(origin + `/api/v1/replies/${reply.id}`)).status()).toBe(200);
    await overview(adminPage, 1, 0);
    await reviewPage.goto(reviewUrl);
    await expect(reviewPage.getByText("Resolved report", { exact: true })).toBeVisible();
    await reviewPage.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(reviewPage.getByRole("heading", { name: "Sign in to review reports." })).toBeVisible();
    await expect(reviewPage.getByText("Private fictional reply concern")).toHaveCount(0);
    await login(reviewPage, "sam.member@example.test");
    await reviewPage.goto(reviewUrl);
    await expect(reviewPage.getByRole("heading", { name: "Report review is restricted." })).toBeVisible();
    await expect(reviewPage.getByText("Private fictional reply concern")).toHaveCount(0);
    expect((await reviewPage.request.get(origin + "/api/v1/moderation/reports/" + reportedReply.id)).status()).toBe(403);
  } finally {
    await Promise.all([member.close(), admin.close(), visitor.close(), moderator.close()]);
  }
});
