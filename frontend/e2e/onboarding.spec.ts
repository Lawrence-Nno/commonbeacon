import { expect, test } from "@playwright/test";

test("three onboarding boards teach through nine accepted answers and corrected misconceptions", async ({ page }, testInfo) => {
  const origin = "http://127.0.0.1:4173";
  const boards = await (await page.request.get(origin + "/api/v1/boards")).json();
  for (const slug of ["getting-started", "product-help", "using-commonbeacon"]) {
    const board = boards.find((item: { slug: string }) => item.slug === slug);
    expect(board).toBeTruthy();
    const response = await page.request.get(origin + `/api/v1/boards/${board.id}/questions?size=100`);
    expect(response.status()).toBe(200);
    const questions = await response.json();
    expect(questions.totalElements).toBe(3);
    for (const question of questions.items) {
      const detail = await (await page.request.get(origin + `/api/v1/questions/${question.id}`)).json();
      const replies = await (await page.request.get(origin + `/api/v1/questions/${question.id}/replies?size=100`)).json();
      expect(replies.totalElements).toBe(5);
      expect(detail.solved).toBe(true);
      expect(detail.acceptedReply.body).not.toContain("Common misconception");
      expect(replies.items.filter((reply: { body: string }) => reply.body.startsWith("Common misconception (incorrect):"))).toHaveLength(4);
      expect(JSON.stringify({ detail, replies })).not.toMatch(/fictional/i);
    }
    await page.goto(origin + "/questions/" + questions.items[0].id);
    await expect(page.getByRole("heading", { name: "Accepted answer", exact: true })).toBeVisible();
    await expect(page.getByText(/Common misconception \(incorrect\):/)).toHaveCount(4);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(slug + "-mobile.png"), fullPage: true });
    await page.setViewportSize({ width: 1280, height: 900 });
  }
});
