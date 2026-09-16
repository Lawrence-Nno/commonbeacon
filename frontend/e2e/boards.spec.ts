import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("administrator creates, edits, archives and reopens a board visible to visitors", async ({
  page,
  browser,
}, testInfo) => {
  const password = process.env.DEMO_PASSWORD;
  expect(
    password,
    "Enable local demo seeding and provide DEMO_PASSWORD to run board smoke tests",
  ).toBeTruthy();
  const name = "Browser board " + randomUUID().slice(0, 8);
  const slug = "smoke-" + randomUUID();
  await page.goto("http://127.0.0.1:4173/login");
  await page.getByLabel("Email address").fill("avery.admin@example.test");
  await page.getByLabel("Password", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("link", { name: "Manage boards" }).click();
  await page.getByLabel("Board name").fill(name);
  await page.getByLabel("Slug", { exact: true }).fill(slug);
  await page
    .getByLabel("Description")
    .fill("Fictional board created by the browser verification.");
  await page.getByRole("button", { name: "Create board", exact: true }).click();
  await expect(page.getByText("Board created.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Edit " + name, exact: true }).click();
  await page.getByLabel("Board name").fill(name + " updated");
  await page.getByLabel("Archived (readable, closed to new activity)").check();
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(
    page.getByText("Board changes saved.", { exact: true }),
  ).toBeVisible();
  const article = page
    .getByRole("article")
    .filter({
      has: page.getByRole("heading", { name: name + " updated", exact: true }),
    });
  await expect(article.getByText("Archived", { exact: true })).toBeVisible();
  const boardPath = await article
    .getByRole("link", { name: "View board" })
    .getAttribute("href");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: testInfo.outputPath("admin-boards-desktop.png"),
    fullPage: true,
  });

  const visitor = await browser.newContext();
  try {
    const publicPage = await visitor.newPage();
    await publicPage.setViewportSize({ width: 390, height: 844 });
    await publicPage.goto("http://127.0.0.1:4173" + boardPath);
    await expect(
      publicPage.getByRole("heading", { name: name + " updated", exact: true }),
    ).toBeVisible();
    await expect(
      publicPage.getByText("Archived board.", { exact: true }),
    ).toBeVisible();
    await publicPage.reload();
    await expect(
      publicPage.getByRole("heading", {
        name: "The first question is still ahead.",
      }),
    ).toBeVisible();
    expect(
      await publicPage.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await publicPage.screenshot({
      path: testInfo.outputPath("archived-board-mobile.png"),
      fullPage: true,
    });
    await page
      .getByRole("button", { name: "Edit " + name + " updated", exact: true })
      .click();
    await page
      .getByLabel("Archived (readable, closed to new activity)")
      .uncheck();
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(
      page.getByText("Board changes saved.", { exact: true }),
    ).toBeVisible();
    await publicPage.reload();
    await expect(
      publicPage.getByRole("heading", { name: name + " updated", exact: true }),
    ).toBeVisible();
    await expect(
      publicPage.getByText("Archived board.", { exact: true }),
    ).toHaveCount(0);
    await publicPage.goto("http://127.0.0.1:4173/");
    await expect(
      publicPage.getByRole("link", { name: new RegExp(name + " updated") }),
    ).toBeVisible();
  } finally {
    await visitor.close();
  }
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in to manage boards." }),
  ).toBeVisible();
});
