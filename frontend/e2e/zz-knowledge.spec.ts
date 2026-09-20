import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("administrator publishes, reconciles live edits and archives knowledge while other roles only read", async ({ browser }, testInfo) => {
  test.setTimeout(210_000);
  const origin = "http://127.0.0.1:4173";
  const admin = await browser.newContext(), member = await browser.newContext(), moderator = await browser.newContext(), visitor = await browser.newContext();
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

  try {
    const edit = await admin.newPage(), read = await visitor.newPage(), memberPage = await member.newPage(), modPage = await moderator.newPage();
    await login(edit, "avery.admin@example.test");
    await login(memberPage, "alex.member@example.test");
    await login(modPage, "morgan.moderator@example.test");
    const baseline = await (await edit.request.get(origin + "/api/v1/moderation/summary")).json();
    const marker = "Journey" + randomUUID().replaceAll("-", "");
    async function publishedCount(delta: number) {
      await modPage.goto(origin + "/moderation");
      await expect(modPage.getByRole("region", { name: "Operational summary" }).locator("dd").nth(2)).toHaveText(String(baseline.publishedArticles + delta));
    }
    async function search(text: string, found: boolean) {
      await read.goto(origin + "/search?q=" + encodeURIComponent(marker + " " + text));
      await expect(read.getByRole("status")).toContainText(found ? "1 results" : "0 results");
    }
    const slug = "guide-" + randomUUID();
    await edit.getByRole("link", { name: "Manage articles", exact: true }).click();
    await edit.getByRole("link", { name: "Create article", exact: true }).click();
    await edit.getByLabel("Article slug").fill(slug);
    await edit.getByLabel("Article title").fill("Fictional knowledge guide");
    await edit.getByLabel("Article body").fill("Private draft <script>plain text only</script>");
    await edit.setViewportSize({ width: 390, height: 844 });
    await edit.screenshot({ path: testInfo.outputPath("article-editor-mobile.png"), fullPage: true });
    expect(await edit.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await edit.getByLabel("Article body").press("Tab");
    await expect(edit.getByRole("button", { name: "Create draft" })).toBeFocused();
    await edit.getByRole("button", { name: "Create draft" }).press("Enter");
    await expect(edit.getByRole("heading", { name: "Edit article" })).toBeVisible();
    const editorUrl = edit.url(); const id = editorUrl.split("/").at(-1)!;
    await edit.reload(); await expect(edit.getByLabel("Article slug")).toHaveAttribute("readonly", "");
    for (const page of [read, memberPage, modPage]) {
      await page.goto(origin + "/knowledge/" + slug);
      await expect(page.getByRole("alert")).toContainText("Article not found");
      expect((await page.request.get(origin + "/api/v1/articles/" + slug)).status()).toBe(404);
      await page.goto(editorUrl);
      await expect(page.getByRole("heading", { name: page === read ? "Sign in to manage articles." : "Article administration is restricted." })).toBeVisible();
      expect((await page.request.get(origin + "/api/v1/admin/articles/" + id)).status()).toBe(page === read ? 401 : 403);
    }
    await publishedCount(0);
    await search("", false);
    await edit.getByLabel("Article body").fill(marker + " telescope " + "x".repeat(2000) + " Draft revised <script>plain text only</script>");
    await edit.route("**/api/v1/admin/articles/" + id, route => route.request().method() === "PATCH" ? route.abort("failed") : route.continue());
    await edit.getByRole("button", { name: "Save article" }).click();
    await expect(edit.getByRole("alert")).toBeVisible();
    await expect(edit.getByLabel("Article body")).toHaveValue(marker + " telescope " + "x".repeat(2000) + " Draft revised <script>plain text only</script>");
    await expect(edit.getByText("Article saved.", { exact: true })).toHaveCount(0);
    await edit.unroute("**/api/v1/admin/articles/" + id);
    await edit.getByRole("button", { name: "Load latest article" }).click();
    await edit.getByRole("button", { name: "Keep my draft after review" }).click();
    await edit.getByRole("button", { name: "Save article" }).click(); await expect(edit.getByText("Article saved.")).toBeVisible();
    await edit.getByRole("button", { name: "Publish article", exact: true }).click();
    await expect(edit.getByText(/Saving changes updates the public article immediately/)).toBeVisible();
    for (const page of [read, memberPage, modPage]) {
      await page.goto(origin + "/knowledge/" + slug);
      await expect(page.getByText(/Draft revised <script>plain text only<\/script>/)).toBeVisible();
      expect(await page.locator("main script").count()).toBe(0);
    }
    await publishedCount(1);
    await search("telescopes", true);
    await read.setViewportSize({ width: 390, height: 844 });
    await read.goto(origin + "/knowledge/" + slug);
    expect(await read.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await read.screenshot({ path: testInfo.outputPath("article-long-mobile.png"), fullPage: true });
    await read.setViewportSize({ width: 1280, height: 900 });
    await read.goto(origin + "/knowledge"); await read.getByRole("link", { name: "Fictional knowledge guide" }).click();
    await read.reload(); await expect(read.getByRole("heading", { name: "Fictional knowledge guide" })).toBeVisible();
    const second = await admin.newPage(); await second.goto(editorUrl); await second.getByLabel("Article body").fill(marker + " My second tab preserved draft.");
    await edit.getByLabel("Article body").fill(marker + " microscope First tab live update."); await edit.getByRole("button", { name: "Save article" }).click();
    await expect(edit.getByText("Article saved.")).toBeVisible();
    await search("telescopes", false);
    await search("microscopes", true);
    await read.goto(origin + "/knowledge/" + slug);
    await second.getByRole("button", { name: "Save article" }).click();
    await expect(second.getByText(/Your draft is preserved/)).toBeVisible();
    await expect(second.getByLabel("Article body")).toHaveValue(marker + " My second tab preserved draft.");
    await second.getByRole("button", { name: "Load latest article" }).click();
    await expect(second.getByText(marker + " microscope First tab live update.", { exact: true })).toBeVisible();
    await second.getByRole("button", { name: "Keep my draft after review" }).click();
    await second.getByRole("button", { name: "Save article" }).click(); await expect(second.getByText("Article saved.")).toBeVisible();
    await read.reload(); await expect(read.getByText(marker + " My second tab preserved draft.")).toBeVisible();
    await read.screenshot({ path: testInfo.outputPath("article-public-desktop.png"), fullPage: true });
    await search("", true);
    await read.goto(origin + "/knowledge/" + slug);
    await second.getByRole("button", { name: "Archive article", exact: true }).click();
    await expect(second.getByText(/archived and read-only/)).toBeVisible();
    await expect(second.getByRole("button", { name: "Save article" })).toHaveCount(0);
    await read.reload(); await expect(read.getByRole("alert")).toContainText("Article not found");
    await read.goto(origin + "/knowledge"); await expect(read.getByRole("link", { name: "Fictional knowledge guide" })).toHaveCount(0);
    await publishedCount(0);
    await search("", false);
    await second.getByRole("link", { name: "Back to articles" }).click();
    await second.getByLabel("Article status").selectOption("ARCHIVED"); await expect(second.getByRole("link", { name: "Fictional knowledge guide" })).toBeVisible();
    await second.goto(editorUrl); await second.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(second.getByRole("heading", { name: "Sign in to manage articles." })).toBeVisible();
    await expect(second.getByLabel("Article body")).toHaveCount(0);
    await login(second, "sam.member@example.test"); await second.goto(editorUrl);
    await expect(second.getByRole("heading", { name: "Article administration is restricted." })).toBeVisible();
    await expect(second.getByText(marker + " My second tab preserved draft.")).toHaveCount(0);
  } finally { await Promise.all([admin.close(), member.close(), moderator.close(), visitor.close()]); }
});
