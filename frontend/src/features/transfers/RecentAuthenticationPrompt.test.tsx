import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RecentAuthenticationPrompt } from "./RecentAuthenticationPrompt";
import { confirmTransferPassword } from "./recentAuthentication";
vi.mock("./recentAuthentication", () => ({ confirmTransferPassword: vi.fn() }));
const grant = { token: "a".repeat(43), expiresAt: "2026-09-22T16:00:00Z" };
const confirm = vi.mocked(confirmTransferPassword);
beforeEach(() => vi.resetAllMocks());
function fill() { fireEvent.change(screen.getByLabelText("Current password"), { target: { value: "test-password" } }); }
describe("RecentAuthenticationPrompt", () => {
  it("clears passwords immediately and returns a grant without displaying it", async () => {
    confirm.mockResolvedValue(grant); const done = vi.fn();
    render(<RecentAuthenticationPrompt actorId="one" scope="DOWNLOAD" onConfirmed={done} onCancel={vi.fn()} />);
    fill(); fireEvent.click(screen.getByText("Confirm password"));
    expect(screen.getByLabelText("Current password")).toHaveValue("");
    await waitFor(() => expect(done).toHaveBeenCalledWith(grant));
    expect(confirm).toHaveBeenCalledWith("test-password", "DOWNLOAD");
    expect(screen.queryByText(grant.token)).not.toBeInTheDocument();
  });
  it("clears a rejected password and permits another confirmation", async () => {
    confirm.mockRejectedValue(new Error("Password confirmation failed."));
    render(<RecentAuthenticationPrompt actorId="one" scope="DOWNLOAD" onConfirmed={vi.fn()} onCancel={vi.fn()} />);
    fill(); fireEvent.click(screen.getByText("Confirm password"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Password confirmation failed.");
    expect(screen.getByLabelText("Current password")).toHaveValue("");
    expect(screen.getByText("Confirm password")).toBeEnabled();
  });
  it("ignores a grant returned after cancellation", async () => {
    let resolve!: (value: typeof grant) => void; confirm.mockImplementation(() => new Promise(r => { resolve = r; }));
    const done = vi.fn(); const cancel = vi.fn();
    render(<RecentAuthenticationPrompt actorId="one" scope="DOWNLOAD" onConfirmed={done} onCancel={cancel} />);
    fill(); fireEvent.click(screen.getByText("Confirm password")); fireEvent.click(screen.getByText("Cancel"));
    await act(async () => resolve(grant)); expect(done).not.toHaveBeenCalled(); expect(cancel).toHaveBeenCalledOnce();
  });
  it("drops password and pending response after switching accounts", async () => {
    let resolve!: (value: typeof grant) => void; confirm.mockImplementation(() => new Promise(r => { resolve = r; }));
    const done = vi.fn(); const props = { scope: "DOWNLOAD" as const, onConfirmed: done, onCancel: vi.fn() };
    const view = render(<RecentAuthenticationPrompt actorId="one" {...props} />);
    fill(); fireEvent.click(screen.getByText("Confirm password")); view.rerender(<RecentAuthenticationPrompt actorId="two" {...props} />);
    await act(async () => resolve(grant)); expect(done).not.toHaveBeenCalled(); expect(screen.getByLabelText("Current password")).toHaveValue("");
  });
  it("clears password on session expiry", () => {
    render(<RecentAuthenticationPrompt actorId="one" scope="DOWNLOAD" onConfirmed={vi.fn()} onCancel={vi.fn()} />);
    fill(); act(() => window.dispatchEvent(new Event("commonbeacon:session-expired")));
    expect(screen.getByLabelText("Current password")).toHaveValue(""); expect(screen.getByRole("alert")).toHaveTextContent("Please sign in again.");
  });
});
