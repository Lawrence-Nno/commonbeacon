import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("visitors search ranked public titles and bodies with global pages and refreshed visibility", async ({ browser }, testInfo) => {
  test.setTimeout(180_000);
  const origin = "http://127.0.0.1:4173", marker = "Search" + randomUUID().replaceAll("-", "");
  const admin = await browser.newContext(), visitor = await browser.newContext();
  try {
    const editor = await admin.newPage(), page = await visitor.newPage();
    await editor.goto(origin + "/login");
    for (let attempt = 0; attempt < 2; attempt++) {
      await editor.getByLabel("Email address").fill("avery.admin@example.test");
      await editor.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD!);
      const pending = editor.waitForResponse((r) => r.url().endsWith("/api/v1/auth/login") && r.request().method() === "POST");
      await editor.getByRole("button", { name: "Sign in", exact: true }).click();
      const response = await pending;
      if (response.status() !== 429 || attempt === 1) { expect(response.status()).toBe(200); break; }
      const seconds = Number(response.headers()["retry-after"]);
      expect(Number.isFinite(seconds) && seconds > 0 && seconds <= 60).toBe(true);
      await editor.waitForTimeout(seconds * 1000);
    }
    await expect(editor.getByRole("button", { name: "Sign out" })).toBeVisible();
    async function mutation(path: string, data: unknown, status = 200, method = "POST") {
      const csrf = await (await editor.request.get(origin + "/api/v1/auth/csrf")).json();
      const response = await editor.request.fetch(origin + path, { method, data, headers: { [csrf.headerName]: csrf.token } });
      expect(response.status(), await response.text()).toBe(status); return response.json();
    }
    const board = await mutation("/api/v1/boards", { slug: "search-" + randomUUID(), name: "Search test", description: "Fictional search test board." }, 201);
    const question = await mutation(`/api/v1/boards/${board.id}/questions`, { title: marker + " 50%_! Question", body: "Literal <script>question snippet</script>" }, 201);
    const articles = [];
    for (let i = 0; i < 21; i++) {
      const article = await mutation("/api/v1/admin/articles", { slug: "search-" + randomUUID(), title: marker + " Article " + i, body: "Literal <script>article snippet</script> " + "word ".repeat(60) }, 201);
      articles.push(await mutation(`/api/v1/admin/articles/${article.id}/publish`, { expectedVersion: article.version }));
    }
    const draft = await mutation("/api/v1/admin/articles", { slug: "private-" + randomUUID(), title: marker + " Private draft", body: "Private draft text must not appear in search." }, 201);
    await mutation(`/api/v1/boards/${board.id}`, { archived: true, expectedVersion: board.version }, 200, "PATCH");
    await page.goto(origin + "/knowledge"); await page.getByRole("link", { name: "Search", exact: true }).click();
    await expect(page.getByText(/Enter words to search/)).toBeVisible();
    await page.getByLabel("Search titles and bodies").fill(marker); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("22 results");
    await expect(page.locator("main ol > li")).toHaveCount(20);
    await expect(page.getByText("Private draft text must not appear in search.")).toHaveCount(0);
    expect(await page.locator("main script").count()).toBe(0);
    await page.screenshot({ path: testInfo.outputPath("search-desktop.png"), fullPage: true });
    await page.getByRole("button", { name: "Next results" }).click();
    await expect(page).toHaveURL(/page=1/); await expect(page.locator("main ol > li")).toHaveCount(2);
    await expect(page.getByText("Community question", { exact: true })).toBeVisible();
    await page.reload(); await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await page.getByRole("link", { name: question.title, exact: true }).click();
    await expect(page.getByText("Literal <script>question snippet</script>")).toBeVisible();
    await page.goBack(); await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await page.getByLabel("Search titles and bodies").fill(marker + " 50%_!"); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("1 results"); await expect(page).toHaveURL(/page=0/);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByLabel("Search titles and bodies").focus(); await page.getByLabel("Search titles and bodies").press("Tab");
    await expect(page.getByRole("button", { name: "Search", exact: true })).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath("search-mobile.png"), fullPage: true });
    const report = await mutation("/api/v1/reports", { questionId: question.id, reason: "Private fictional search visibility concern" }, 201);
    const reviewResponse = await editor.request.get(origin + `/api/v1/moderation/reports/${report.id}`);
    expect(reviewResponse.status()).toBe(200);
    const review = await reviewResponse.json();
    await mutation(`/api/v1/moderation/reports/${report.id}/resolve`, { decision: "HIDE", resolutionNote: "Hide fictional search fixture", expectedVersion: review.report.version, expectedTargetVersion: review.context.question.version });
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("0 results");
    await expect(page.getByRole("link", { name: question.title })).toHaveCount(0);
    const context = await (await editor.request.get(origin + `/api/v1/moderation/questions/${question.id}`)).json();
    await mutation(`/api/v1/moderation/questions/${question.id}/restore`, { reason: "Restore fictional search fixture", expectedTargetVersion: context.question.version });
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("1 results");
    await mutation(`/api/v1/admin/articles/${articles[0].id}/archive`, { expectedVersion: articles[0].version });
    await page.getByLabel("Search titles and bodies").fill(marker); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("21 results");
    await mutation(`/api/v1/admin/articles/${draft.id}/publish`, { expectedVersion: draft.version });
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("22 results");
    const bodyDraft = await mutation("/api/v1/admin/articles", { slug: "body-" + randomUUID(), title: "Fictional astronomy reference", body: marker + " telescope instructions appear only in this body." }, 201);
    const bodyArticle = await mutation(`/api/v1/admin/articles/${bodyDraft.id}/publish`, { expectedVersion: bodyDraft.version });
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("23 results");
    await page.getByRole("button", { name: "Next results" }).click();
    await expect(page.locator("main ol > li").last().getByRole("link")).toHaveText("Fictional astronomy reference");
    await page.getByLabel("Search titles and bodies").fill(`"${marker} telescope"`);
    await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("1 results");
    await expect(page.getByRole("link", { name: "Fictional astronomy reference" })).toBeVisible();
    await mutation(`/api/v1/admin/articles/${bodyArticle.id}`, { title: bodyArticle.title, body: marker + " microscope instructions replace the old body.", expectedVersion: bodyArticle.version }, 200, "PATCH");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("0 results");
    await page.getByLabel("Search titles and bodies").fill(marker + " microscopes"); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("1 results");
    await page.getByLabel("Search titles and bodies").fill("the and of"); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByRole("status")).toContainText("0 results");
    await page.getByLabel("Search titles and bodies").fill(marker); await page.getByLabel("Search titles and bodies").press("Enter");
    await page.getByLabel("Search titles and bodies").fill("unmatched-" + randomUUID()); await page.getByLabel("Search titles and bodies").press("Enter");
    await expect(page.getByText("No matching results on this page.")).toBeVisible();
  } finally { await admin.close(); await visitor.close(); }
});
