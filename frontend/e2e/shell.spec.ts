import { expect, test } from "@playwright/test";

test("real backend health is visible through the Vite proxy", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("http://127.0.0.1:4173/");
  await expect(
    page.getByRole("heading", { name: "A light is on." }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", {
      name: "The first conversation is still ahead.",
    }),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("home-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "About this space" }).click();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: /Better answers begin/ }),
  ).toBeVisible();
  await page.goto("http://127.0.0.1:4173/no-such-page");
  await page.getByRole("link", { name: "Back to the community" }).click();
  await expect(
    page.getByRole("heading", { name: "A light is on." }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});

test("mobile shell fits and supports keyboard navigation", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("http://127.0.0.1:4173/");
  await expect(
    page.getByRole("heading", { name: "A light is on." }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("main")).toBeFocused();
  await page.screenshot({
    path: testInfo.outputPath("home-mobile.png"),
    fullPage: true,
  });
});

test("unavailable upstream produces a usable retry state", async ({
  page,
}, testInfo) => {
  await page.goto("http://127.0.0.1:4174/");
  await expect(
    page.getByRole("heading", { name: "Connection interrupted" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("button", { name: "Try again" })).toBeEnabled();
  await expect(
    page.getByRole("heading", { name: "Connection interrupted" }),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("outage.png"),
    fullPage: true,
  });
});
