import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { ApiError } from "../../lib/http";
import { ReplyForm } from "./ReplyForm";
import { readReplyPage } from "./api";

it("keeps a rejected draft and displays its validation error", async () => {
  const save = vi
    .fn()
    .mockRejectedValue(
      new ApiError("http", "Check your reply.", 400, undefined, {
        body: "Use a nonblank reply.",
      }),
    );
  render(<ReplyForm onSave={save} />);
  await userEvent.type(screen.getByLabelText("Your reply"), "draft");
  await userEvent.click(screen.getByRole("button", { name: "Post reply" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Check your reply",
  );
  expect(screen.getByLabelText("Your reply")).toHaveValue("draft");
  expect(screen.getByText("Use a nonblank reply.")).toBeVisible();
});
it("disables duplicate submissions while pending and clears successful drafts", async () => {
  let finish!: () => void;
  const save = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  render(<ReplyForm onSave={save} />);
  await userEvent.type(screen.getByLabelText("Your reply"), "answer");
  await userEvent.click(screen.getByRole("button", { name: "Post reply" }));
  expect(screen.getByRole("button", { name: "Saving..." })).toBeDisabled();
  expect(save).toHaveBeenCalledTimes(1);
  finish();
  await screen.findByRole("button", { name: "Post reply" });
  expect(screen.getByLabelText("Your reply")).toHaveValue("");
});
it("requires an explicit reload after a conflict and preserves the draft when reload fails", async () => {
  const reload = vi
    .fn()
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValue("Latest answer");
  render(
    <ReplyForm
      initial="My draft"
      editing
      onSave={async () => {
        throw new ApiError("http", "This reply changed.", 409);
      }}
      onReload={reload}
    />,
  );
  await userEvent.click(screen.getByRole("button", { name: "Save reply" }));
  await userEvent.click(
    await screen.findByRole("button", {
      name: "Reload latest and discard draft",
    }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Your draft is still here",
  );
  expect(screen.getByLabelText("Edit reply")).toHaveValue("My draft");
  await userEvent.click(
    screen.getByRole("button", { name: "Reload latest and discard draft" }),
  );
  expect(screen.getByLabelText("Edit reply")).toHaveValue("Latest answer");
});
it("keeps archived drafts visible and prevents saving", () => {
  render(<ReplyForm initial="Unsent answer" closed onSave={vi.fn()} />);
  expect(screen.getByLabelText("Your reply")).toHaveValue("Unsent answer");
  expect(screen.getByRole("button", { name: "Post reply" })).toBeDisabled();
});
it("rejects malformed reply pages", () => {
  expect(() =>
    readReplyPage({
      items: [{}],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    }),
  ).toThrow("unexpected");
  expect(() =>
    readReplyPage({
      items: [],
      page: 0,
      size: 0,
      totalElements: 0,
      totalPages: 0,
    }),
  ).toThrow("unexpected");
});
