import { expect, test } from "@playwright/test";
import type { BrowserContext, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("members publish and edit questions, recover stale drafts, and cannot edit another member's question", async ({
  browser,
}, testInfo) => {
  test.setTimeout(60_000);
  const password = process.env.DEMO_PASSWORD;
  expect(
    password,
    "Local demo seeding and DEMO_PASSWORD are required",
  ).toBeTruthy();
  const owner = await browser.newContext();
  const other = await browser.newContext();
  const admin = await browser.newContext();
  const visitor = await browser.newContext();
  const origin = "http://127.0.0.1:4173";
  async function login(context: BrowserContext, email: string) {
    const page = await context.newPage();
    await page.goto(origin + "/login");
    await page.getByLabel("Email address").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(password!);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
    return page;
  }
  async function mutation(
    page: Page,
    path: string,
    data: unknown,
    method = "POST",
  ) {
    const csrf = await (
      await page.request.get(origin + "/api/v1/auth/csrf")
    ).json();
    return page.request.fetch(origin + path, {
      method,
      headers: { [csrf.headerName]: csrf.token },
      data,
    });
  }
  try {
    const ownerPage = await login(owner, "alex.member@example.test");
    const otherPage = await login(other, "sam.member@example.test");
    const adminPage = await login(admin, "avery.admin@example.test");
    const createdBoard = await mutation(adminPage, "/api/v1/boards", {
      name: "Question smoke " + randomUUID().slice(0, 8),
      slug: "question-smoke-" + randomUUID(),
      description: "Fictional board for question browser verification.",
    });
    expect(createdBoard.status()).toBe(201);
    const board = await createdBoard.json();
    const title = "How can I set up my workspace? " + randomUUID().slice(0, 8);
    await ownerPage.goto(origin + "/boards/" + board.id);
    await ownerPage.getByRole("link", { name: "Ask a question" }).click();
    await ownerPage.getByLabel("Question title").fill(title);
    await ownerPage
      .getByLabel("Details", { exact: true })
      .fill(
        "I followed the setup guide.\nWhat should I configure next? <b>This is plain text.</b>",
      );
    await ownerPage.getByRole("button", { name: "Publish question" }).click();
    await expect(
      ownerPage.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    const questionPath = new URL(ownerPage.url()).pathname;
    await ownerPage.reload();
    await expect(
      ownerPage.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(ownerPage.locator(".question-body b")).toHaveCount(0);
    await ownerPage.getByRole("link", { name: "Edit question" }).click();
    const updatedTitle = title + " updated";
    await ownerPage.getByLabel("Question title").fill(updatedTitle);
    await ownerPage.getByRole("button", { name: "Save question" }).click();
    await expect(
      ownerPage.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();

    await otherPage.goto(origin + questionPath);
    await expect(
      otherPage.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();
    await expect(
      otherPage.getByRole("link", { name: "Edit question" }),
    ).toHaveCount(0);
    const forbidden = await mutation(
      otherPage,
      "/api/v1" + questionPath,
      {
        title: "Forged ownership edit",
        body: "Another member tries to edit.",
        expectedVersion: 1,
      },
      "PATCH",
    );
    expect(forbidden.status()).toBe(403);
    await otherPage.goto(origin + questionPath + "/edit");
    await expect(
      otherPage.getByRole("heading", {
        name: "Only the author can edit this question.",
      }),
    ).toBeVisible();

    await ownerPage.getByRole("link", { name: "Edit question" }).click();
    const secondTab = await owner.newPage();
    await secondTab.goto(origin + questionPath + "/edit");
    await expect(secondTab.getByLabel("Question title")).toHaveValue(
      updatedTitle,
    );
    await ownerPage
      .getByLabel("Details", { exact: true })
      .fill("The first tab saved these useful details.");
    await ownerPage.getByRole("button", { name: "Save question" }).click();
    await expect(
      ownerPage.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();
    await secondTab
      .getByLabel("Details", { exact: true })
      .fill("The second tab still has this draft.");
    await secondTab.getByRole("button", { name: "Save question" }).click();
    await expect(secondTab.getByRole("alert")).toContainText("changed");
    await expect(secondTab.getByLabel("Details", { exact: true })).toHaveValue(
      "The second tab still has this draft.",
    );
    await secondTab
      .getByRole("button", { name: "Reload latest and discard draft" })
      .click();
    await expect(secondTab.getByLabel("Details", { exact: true })).toHaveValue(
      "The first tab saved these useful details.",
    );
    await secondTab
      .getByLabel("Details", { exact: true })
      .fill("Merged details after reloading the latest question.");
    await secondTab.getByRole("button", { name: "Save question" }).click();
    await expect(
      secondTab.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();
    await secondTab.setViewportSize({ width: 1440, height: 1000 });
    await secondTab.screenshot({
      path: testInfo.outputPath("question-desktop.png"),
      fullPage: true,
    });

    // A second member can reply, edit, and recover a stale draft.
    await otherPage.goto(origin + questionPath);
    await otherPage
      .getByLabel("Your reply")
      .fill("Try the setup guide. <b>Plain text answer.</b>");
    await otherPage.getByRole("button", { name: "Post reply" }).click();
    await expect(otherPage.locator(".reply-body")).toContainText(
      "Try the setup guide.",
    );
    await otherPage.reload();
    await expect(otherPage.locator(".reply-body")).toContainText(
      "Plain text answer.",
    );
    await expect(otherPage.locator(".reply-body b")).toHaveCount(0);
    const replies = await (
      await otherPage.request.get(
        origin + "/api/v1" + questionPath + "/replies",
      )
    ).json();
    const reply = replies.items[0];
    expect(
      (
        await mutation(
          ownerPage,
          "/api/v1/replies/" + reply.id,
          { body: "Forged edit", expectedVersion: 0 },
          "PATCH",
        )
      ).status(),
    ).toBe(403);
    await otherPage
      .getByRole("button", { name: "Edit reply", exact: true })
      .click();
    const replyTab = await other.newPage();
    await replyTab.goto(origin + questionPath);
    await replyTab
      .getByRole("button", { name: "Edit reply", exact: true })
      .click();
    await otherPage
      .getByLabel("Edit reply", { exact: true })
      .fill("Updated answer from the first tab.");
    await otherPage.getByRole("button", { name: "Save reply" }).click();
    await expect(otherPage.locator(".reply-body")).toHaveText(
      "Updated answer from the first tab.",
    );
    await replyTab
      .getByLabel("Edit reply", { exact: true })
      .fill("Keep my competing draft.");
    await replyTab.getByRole("button", { name: "Save reply" }).click();
    await expect(replyTab.getByRole("alert")).toContainText("changed");
    await expect(
      replyTab.getByLabel("Edit reply", { exact: true }),
    ).toHaveValue("Keep my competing draft.");
    await replyTab
      .getByRole("button", { name: "Reload latest and discard draft" })
      .click();
    await expect(
      replyTab.getByLabel("Edit reply", { exact: true }),
    ).toHaveValue("Updated answer from the first tab.");
    await replyTab
      .getByLabel("Edit reply", { exact: true })
      .fill("Merged helpful answer.");
    await replyTab.getByRole("button", { name: "Save reply" }).click();
    await expect(replyTab.locator(".reply-body")).toHaveText(
      "Merged helpful answer.",
    );
    await replyTab.close();

    // The question owner accepts, replaces with their own reply, and clears.
    await ownerPage.goto(origin + questionPath);
    await ownerPage.getByRole("button", { name: "Accept as solution" }).click();
    await expect(ownerPage.getByRole("heading", { name: "Accepted answer" })).toBeVisible();
    await ownerPage.reload();
    await expect(ownerPage.locator(".accepted-answer .reply-body")).toHaveText("Merged helpful answer.");
    await otherPage.reload();
    await expect(otherPage.getByRole("button", { name: "Clear solution" })).toHaveCount(0);
    const selected = await (await ownerPage.request.get(origin + "/api/v1" + questionPath)).json();
    expect((await mutation(otherPage, "/api/v1" + questionPath + "/accepted-reply", {
      replyId: reply.id, expectedVersion: selected.version,
    }, "PUT")).status()).toBe(403);
    await ownerPage.getByLabel("Your reply").fill("My own additional solution.");
    await ownerPage.getByRole("button", { name: "Post reply" }).click();
    await ownerPage.getByRole("button", { name: "Replace solution with this reply" }).click();
    await expect(ownerPage.locator(".accepted-answer .reply-body")).toHaveText("My own additional solution.");
    await ownerPage.getByRole("button", { name: "Clear solution" }).click();
    await expect(ownerPage.getByRole("heading", { name: "Accepted answer" })).toHaveCount(0);
    await ownerPage.goto(origin + "/boards/" + board.id);
    await ownerPage.getByLabel("Show questions").selectOption("unanswered");
    await expect(ownerPage.getByRole("link", { name: updatedTitle, exact: true })).toBeVisible();
    await ownerPage.getByLabel("Show questions").selectOption("solved");
    await expect(ownerPage.getByRole("heading", { name: "No questions match this filter." })).toBeVisible();
    await ownerPage.goto(origin + questionPath);
    await ownerPage.locator("#reply-" + reply.id).getByRole("button", { name: "Accept as solution" }).click();
    await expect(ownerPage.getByRole("heading", { name: "Accepted answer" })).toBeVisible();
    await ownerPage.goto(origin + "/boards/" + board.id + "?status=solved");
    await expect(ownerPage.getByRole("link", { name: updatedTitle, exact: true })).toBeVisible();
    await expect(ownerPage.locator(".question-card").getByText("Solved", { exact: true })).toBeVisible();

    const publicPage = await visitor.newPage();
    await publicPage.setViewportSize({ width: 390, height: 844 });
    await publicPage.goto(origin + "/boards/" + board.id);
    await publicPage
      .getByRole("link", { name: updatedTitle, exact: true })
      .click();
    await expect(
      publicPage.getByText(
        "Merged details after reloading the latest question.",
      ),
    ).toBeVisible();
    await publicPage.reload();
    await expect(
      publicPage.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();
    expect(
      await publicPage.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(publicPage.locator(".accepted-answer .reply-body")).toHaveText(
      "Merged helpful answer.",
    );
    await expect(
      publicPage.getByRole("button", { name: "Edit reply", exact: true }),
    ).toHaveCount(0);
    await publicPage.screenshot({
      path: testInfo.outputPath("question-mobile.png"),
      fullPage: true,
    });

    const archived = await mutation(
      adminPage,
      "/api/v1/boards/" + board.id,
      { expectedVersion: board.version, archived: true },
      "PATCH",
    );
    expect(archived.status()).toBe(200);
    await otherPage.reload();
    await expect(
      otherPage.getByRole("button", { name: "Post reply" }),
    ).toBeDisabled();
    await ownerPage.goto(origin + questionPath);
    await expect(
      ownerPage.getByText(
        "This board is archived. You can read this question, but changes are closed.",
      ),
    ).toBeVisible();
    await expect(
      ownerPage.getByRole("link", { name: "Edit question" }),
    ).toHaveCount(0);
    await expect(ownerPage.getByRole("button", { name: "Clear solution" })).toHaveCount(0);
    expect(
      (
        await mutation(ownerPage, "/api/v1/boards/" + board.id + "/questions", {
          title: "A closed board question",
          body: "This board is already closed.",
        })
      ).status(),
    ).toBe(409);
    await ownerPage.goto(origin + questionPath + "/edit");
    await expect(
      ownerPage.getByRole("button", { name: "Save question" }),
    ).toBeDisabled();
    await publicPage.reload();
    await expect(
      publicPage.getByRole("heading", { name: updatedTitle, exact: true }),
    ).toBeVisible();
  } finally {
    await Promise.all([
      owner.close(),
      other.close(),
      admin.close(),
      visitor.close(),
    ]);
  }
});
