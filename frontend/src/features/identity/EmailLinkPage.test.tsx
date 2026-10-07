import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { EmailLinkEntry, EmailLinkPage } from "./EmailLinkPage";
import { takeEmailLink } from "./emailLink";

const token = "a".repeat(43);
afterEach(() => window.history.replaceState(null, "", "/"));

it("takes the exact fragment into memory and removes secrets from URL and history state", () => {
  const storage = vi.spyOn(Storage.prototype, "setItem");
  window.history.replaceState({ token }, "", `/verify-email#token=${token}`);
  expect(takeEmailLink()).toEqual({ kind: "verification", token });
  expect(window.location.href).not.toContain(token);
  expect(window.history.state).toBeNull();
  expect(takeEmailLink()).toEqual({ kind: "verification", token: undefined });
  expect(storage).not.toHaveBeenCalled();
});

it("rejects and scrubs malformed fragments, duplicate tokens, and query tokens", () => {
  for (const suffix of ["#token=short", `#token=${token}&next=https://evil.test`, `#token=${token}&token=${token}`, `?token=${token}`, `?next=evil#token=${token}`, "#token=%61" + "a".repeat(42)]) {
    window.history.replaceState(null, "", "/reset-password" + suffix);
    expect(takeEmailLink()).toEqual({ kind: "password-reset", token: undefined });
    expect(window.location.href).toMatch(/\/reset-password$/);
  }
});

it("recognizes all fixed purpose routes without accepting unrelated paths", () => {
  window.history.replaceState(null, "", `/confirm-email-change#token=${token}`);
  expect(takeEmailLink()?.kind).toBe("email-change");
  window.history.replaceState(null, "", "/about#section");
  expect(takeEmailLink()).toBeUndefined();
  expect(window.location.hash).toBe("#section");
});

it("scrubs subsequent same-document paste and back-navigation fragments and resets the form", async () => {
  window.history.replaceState(null, "", "/verify-email");
  render(<EmailLinkEntry initialLink={{ kind: "verification", token }} />);
  await userEvent.click(screen.getByRole("button", { name: "Confirm email" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("could not process");
  window.location.hash = "token=bad&next=evil";
  await waitFor(() => expect(window.location.hash).toBe(""));
  expect(await screen.findByRole("alert")).toHaveTextContent("incomplete");
  window.location.hash = `token=${"b".repeat(43)}`;
  await waitFor(() => expect(window.location.hash).toBe(""));
  expect(await screen.findByRole("button", { name: "Confirm email" })).toBeEnabled();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("opening a valid page never submits; explicit confirmation invokes exactly one adapter", async () => {
  const confirm = vi.fn().mockResolvedValue(undefined);
  render(<EmailLinkPage link={{ kind: "verification", token }} confirm={confirm} />);
  expect(confirm).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "Confirm email" }));
  await waitFor(() => expect(confirm).toHaveBeenCalledExactlyOnceWith({ token }));
  expect(await screen.findByRole("status")).toHaveTextContent("Your request is complete");
});

it("reset requires matching bounded passwords and passes them only after submission", async () => {
  const confirm = vi.fn().mockResolvedValue(undefined);
  render(<EmailLinkPage link={{ kind: "password-reset", token }} confirm={confirm} />);
  await userEvent.type(screen.getByLabelText("New password", { exact: true }), "new-password-123");
  await userEvent.type(screen.getByLabelText("Confirm new password"), "other-password-123");
  await userEvent.click(screen.getByRole("button", { name: "Reset password" }));
  expect(confirm).not.toHaveBeenCalled();
  expect(screen.getByRole("alert")).toHaveTextContent("same password");
  await userEvent.clear(screen.getByLabelText("Confirm new password"));
  await userEvent.type(screen.getByLabelText("Confirm new password"), "new-password-123");
  await userEvent.click(screen.getByRole("button", { name: "Reset password" }));
  await waitFor(() => expect(confirm).toHaveBeenCalledExactlyOnceWith({ token, password: "new-password-123" }));
  expect(await screen.findByRole("status")).toBeVisible();
});

it("missing tokens cannot submit and the unwired stage-six default makes no request", async () => {
  const fetch = vi.spyOn(window, "fetch");
  const view = render(<EmailLinkPage link={{ kind: "email-change" }} />);
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("Open the full link");
  view.rerender(<EmailLinkPage link={{ kind: "email-change", token }} />);
  await userEvent.click(screen.getByRole("button", { name: "Confirm email" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("could not process");
  expect(fetch).not.toHaveBeenCalled();
});

it("disables concurrent submit and hides adapter error bodies", async () => {
  let reject!: (error: Error) => void;
  const confirm = vi.fn(() => new Promise<void>((_, failure) => { reject = failure; }));
  render(<EmailLinkPage link={{ kind: "verification", token }} confirm={confirm} />);
  await userEvent.click(screen.getByRole("button", { name: "Confirm email" }));
  expect(screen.getByRole("button", { name: "Working…" })).toBeDisabled();
  reject(new Error("private provider details"));
  expect(await screen.findByRole("alert")).not.toHaveTextContent("private provider");
  expect(confirm).toHaveBeenCalledTimes(1);
});
