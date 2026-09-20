import { act, render as renderView, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement } from "react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ReportControl } from "./ReportControl";
import { createReport, readReportReceipt } from "./api";

const auth = vi.hoisted(() => ({ user: null as { id: string } | null }));
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => auth }));
const receipt = { id: "report-1", status: "OPEN", createdAt: "2026-09-19T00:00:00Z" };
function render(ui: ReactElement) {
  const client = new QueryClient();
  client.setQueryData(["moderation", "member-1", "summary"], { openReports: 0 });
  return { client, ...renderView(ui, { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }) };
}
beforeEach(() => { auth.user = { id: "member-1" }; });
afterEach(() => vi.unstubAllGlobals());

function mockRequests(report: () => Promise<Response>) {
  const fetch = vi.fn(async (url: string) => url.endsWith("/csrf")
    ? Response.json({ token: "test-token", headerName: "X-CSRF-TOKEN" }) : report());
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
async function open() {
  await userEvent.click(screen.getByRole("button", { name: "Report question" }));
  await userEvent.type(screen.getByLabelText("Reason for reporting"), "  Private reason  ");
}

it("submits a trimmed reason with CSRF and shows only confirmed success", async () => {
  const fetch = mockRequests(async () => Response.json(receipt, { status: 201 }));
  const { client } = render(<ReportControl target={{ questionId: "q1" }} />);
  await open();
  await userEvent.click(screen.getByRole("button", { name: "Submit report" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Your report was submitted.");
  expect(client.getQueryState(["moderation", "member-1", "summary"])?.isInvalidated).toBe(true);
  expect(fetch).toHaveBeenLastCalledWith("/api/v1/reports", expect.objectContaining({
    method: "POST", credentials: "same-origin",
    headers: expect.objectContaining({ "X-CSRF-TOKEN": "test-token" }),
    body: JSON.stringify({ questionId: "q1", reason: "Private reason" }),
  }));
  expect(screen.queryByLabelText("Reason for reporting")).not.toBeInTheDocument();
});

it.each([400, 401, 404, 409, 500])("preserves rejected report text on HTTP %s", async (status) => {
  mockRequests(async () => Response.json({ detail: "Report rejected", fieldErrors: { reason: "Review this reason" } },
    { status, headers: { "Content-Type": "application/problem+json" } }));
  render(<ReportControl target={{ questionId: "q1" }} />);
  await open();
  await userEvent.click(screen.getByRole("button", { name: "Submit report" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Report rejected");
  expect(screen.getByLabelText("Reason for reporting")).toHaveValue("  Private reason  ");
  expect(screen.getByText("Review this reason")).toBeVisible();
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
});

it("does not duplicate pending submissions or leak a late result after account switching", async () => {
  let finish!: (response: Response) => void;
  const fetch = mockRequests(() => new Promise<Response>((resolve) => { finish = resolve; }));
  const { rerender } = render(<ReportControl target={{ questionId: "q1" }} />);
  await open();
  await userEvent.click(screen.getByRole("button", { name: "Submit report" }));
  expect(screen.getByRole("button", { name: "Submitting report..." })).toBeDisabled();
  expect(fetch).toHaveBeenCalledTimes(2);
  auth.user = { id: "member-2" };
  rerender(<ReportControl target={{ questionId: "q1" }} />);
  await act(async () => finish(Response.json(receipt)));
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Report question" }));
  expect(screen.getByLabelText("Reason for reporting")).toHaveValue("");
  auth.user = null;
  rerender(<ReportControl target={{ questionId: "q1" }} />);
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});

it("validates trimmed bounds and preserves the draft on network failure", async () => {
  const fetch = mockRequests(async () => { throw new TypeError("offline"); });
  render(<ReportControl target={{ questionId: "q1" }} />);
  await userEvent.click(screen.getByRole("button", { name: "Report question" }));
  await userEvent.type(screen.getByLabelText("Reason for reporting"), "     ");
  await userEvent.click(screen.getByRole("button", { name: "Submit report" }));
  expect(screen.getByText("Use 5–2,000 characters after trimming.")).toBeVisible();
  expect(fetch).not.toHaveBeenCalled();
  await userEvent.clear(screen.getByLabelText("Reason for reporting"));
  await userEvent.type(screen.getByLabelText("Reason for reporting"), "Save my reason");
  await userEvent.click(screen.getByRole("button", { name: "Submit report" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Check your connection");
  expect(screen.getByLabelText("Reason for reporting")).toHaveValue("Save my reason");
});

it("decodes only valid receipts and submits reply targets without a question target", async () => {
  expect(() => readReportReceipt({ ...receipt, status: "RESOLVED" })).toThrow();
  const fetch = mockRequests(async () => Response.json(receipt));
  await createReport({ replyId: "r1" }, "Reply concern");
  expect(fetch).toHaveBeenLastCalledWith("/api/v1/reports", expect.objectContaining({
    body: JSON.stringify({ replyId: "r1", reason: "Reply concern" }),
  }));
});
