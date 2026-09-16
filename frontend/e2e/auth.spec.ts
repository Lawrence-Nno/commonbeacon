import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("registration, login, reload, logout, and expired sessions work with real cookies", async ({
  page,
}, testInfo) => {
  const email = `smoke-${randomUUID()}@example.test`;
  const password = "a-long-test-password-42";
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("http://127.0.0.1:4173/register");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Display name").fill("Smoke Member");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.screenshot({
    path: testInfo.outputPath("register-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Your account has been created.",
  );
  await expect(page.getByRole("button", { name: "Sign out" })).toHaveCount(0);
  await page.getByRole("link", { name: "Continue to sign in" }).click();
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await page.reload();
  await expect(page.getByText("Smoke Member", { exact: true })).toBeVisible();
  const cookie = (await page.context().cookies()).find(
    (value) => value.name === "JSESSIONID",
  );
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Lax");
  const denied = await page.request.post(
    "http://127.0.0.1:4173/api/v1/auth/logout",
  );
  expect(denied.status()).toBe(403);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("link", { name: "Join the community" }),
  ).toBeVisible();

  await page.goto("http://127.0.0.1:4173/login");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText(
    "Email or password is incorrect.",
  );
  await expect(page.getByLabel("Email address")).toHaveValue(email);
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("login-mobile.png"),
    fullPage: true,
  });
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();

  // Invalidate this real session outside the UI, as another tab can do.
  const csrf = await (
    await page.request.get("http://127.0.0.1:4173/api/v1/auth/csrf")
  ).json();
  const signedOut = await page.request.post(
    "http://127.0.0.1:4173/api/v1/auth/logout",
    { headers: { [csrf.headerName]: csrf.token } },
  );
  expect(signedOut.status()).toBe(204);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(page.getByRole("alert")).toHaveText(
    "Your session expired. Please sign in again.",
  );
  await expect(page.getByText("Smoke Member", { exact: true })).toHaveCount(0);
  // Retain only the fictional address so a developer can identify this smoke-test row.
  await testInfo.attach("created-account", {
    body: email,
    contentType: "text/plain",
  });
});
