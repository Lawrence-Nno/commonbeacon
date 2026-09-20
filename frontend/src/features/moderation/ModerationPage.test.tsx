import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { ModerationPage } from "./ModerationPage";
import { readReportDetail, readReportPage } from "./reviewApi";

const moderator = { id: "mod", displayName: "Morgan", role: "MODERATOR" as const };
const actor = { id: "member", displayName: "Alex" };
const report = { id: "report1", status: "OPEN", reason: "Private report reason", reporter: actor,
  targetKind: "REPLY", targetId: "r1", questionId: "q1", createdAt: "2026-09-19T00:00:00Z",
  updatedAt: "2026-09-19T00:00:00Z", version: 0, resolvedAt: null, resolver: null, resolutionDecision: null, resolutionNote: null };
const detail = { report, context: { board: { id: "b1", name: "Help", archived: true },
  question: { id: "q1", title: "Hidden question title", body: "Hidden <script>plain text</script>", author: actor, visibility: "HIDDEN", version: 2, acceptedReplyId: "r1" },
  reply: { id: "r1", body: "Reported reply text", author: actor, visibility: "VISIBLE", version: 0 },
  targetKind: "REPLY", targetId: "r1", effectivePublicVisibility: false }, availableDecisions: ["DISMISS", "HIDE"] };
const page = { items: [report], page: 0, size: 20, totalElements: 21, totalPages: 2 };

function SwitchAccount() {
  const auth = useAuth();
  return <><button onClick={() => void auth.setSession({ ...actor, role: "MEMBER" })}>Switch to member</button>
    <button onClick={() => void auth.setSession(null)}>Expire session</button></>;
}
function setup(route = "/moderation", role: string | null = "MODERATOR", responder?: (url: string, init?: RequestInit) => Promise<Response>) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return role === null ? new Response(null, { status: 401 }) : Response.json({ ...moderator, role });
    if (url.endsWith("/moderation/summary")) return Response.json({ unansweredQuestions: 0, openReports: 21, publishedArticles: 0 });
    if (responder) return responder(url, init);
    return Response.json(url.includes("/reports/") ? detail : page);
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider>
    <SwitchAccount /><Routes><Route path="/moderation" element={<ModerationPage />} />
      <Route path="/moderation/reports/:reportId" element={<ModerationPage />} /></Routes>
  </AuthProvider></MemoryRouter></QueryClientProvider>);
  return { client, mock };
}
afterEach(() => vi.unstubAllGlobals());

it.each(["MODERATOR", "ADMINISTRATOR"])("lets %s page/filter reports and inspect hidden plain-text context", async (role) => {
  const { mock } = setup("/moderation", role);
  await screen.findByText("Private report reason");
  await userEvent.click(screen.getByRole("button", { name: "Next reports" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("page=1"), expect.anything()));
  await userEvent.selectOptions(screen.getByLabelText("Report status"), "RESOLVED");
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("status=RESOLVED&page=0"), expect.anything()));
  await userEvent.click(await screen.findByRole("link", { name: "Review reply report" }));
  await screen.findByText("Reported reply text");
  expect(screen.getByText("Hidden <script>plain text</script>")).toBeVisible();
  expect(document.querySelector("script")).toBeNull();
  expect(screen.getByText(/hidden question keeps it private/)).toBeVisible();
  expect(screen.getByRole("button", { name: "Resolve report" })).toBeDisabled();
  expect(screen.getByRole("link", { name: "Back to reports" })).toHaveAttribute("href", "/moderation?status=RESOLVED&page=0");
});

it.each(["MEMBER", null])("blocks %s before fetching privileged data", async (role) => {
  const { mock } = setup("/moderation/reports/report1", role);
  await screen.findByRole("heading", { name: role ? "Report review is restricted." : "Sign in to review reports." });
  expect(mock.mock.calls.every(([url]) => url.endsWith("/auth/me"))).toBe(true);
  expect(screen.queryByText("Private report reason")).not.toBeInTheDocument();
});

it("shows empty and invalid-filter states without inventing results", async () => {
  const { mock } = setup("/moderation?page=-1", "MODERATOR", async () => Response.json({ ...page, items: [], totalElements: 0, totalPages: 0 }));
  await screen.findByText(/Invalid report filters/);
  expect(mock.mock.calls.some(([url]) => url.includes("/moderation/reports?"))).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "Reset filters" }));
  expect(await screen.findByText("No reports with this status.")).toBeVisible();
});

it.each([403, 404, 500])("handles HTTP %s without exposing cached detail", async (status) => {
  setup("/moderation/reports/report1", "MODERATOR", async () => Response.json({ detail: "Unavailable" }, { status, headers: { "Content-Type": "application/problem+json" } }));
  await screen.findByRole("alert");
  expect(screen.queryByText("Reported reply text")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Retry reports" }) !== null).toBe(status === 500);
});

it("clears loaded private data on session expiry", async () => {
  const { client } = setup("/moderation/reports/report1");
  await screen.findByText("Private report reason");
  await userEvent.click(screen.getByRole("button", { name: "Expire session" }));
  await screen.findByRole("heading", { name: "Sign in to review reports." });
  expect(screen.queryByText("Private report reason")).not.toBeInTheDocument();
  expect(client.getQueryCache().getAll()).toHaveLength(0);
});

it("cancels a pending privileged request and discards its late response on account switch", async () => {
  let finish!: (response: Response) => void;
  let signal: AbortSignal | null | undefined;
  const { client } = setup("/moderation/reports/report1", "MODERATOR", (_url, init) => {
    signal = init?.signal;
    return new Promise((resolve) => { finish = resolve; });
  });
  await screen.findByText("Loading report context...");
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: "Switch to member" }));
  await screen.findByRole("heading", { name: "Report review is restricted." });
  expect(signal?.aborted).toBe(true);
  await act(async () => finish(Response.json(detail)));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryByText("Private report reason")).not.toBeInTheDocument();
});

it("rejects malformed privileged responses", () => {
  expect(() => readReportPage({ ...page, size: 101 })).toThrow();
  expect(() => readReportDetail({ ...detail, context: { ...detail.context, effectivePublicVisibility: "yes" } })).toThrow();
  expect(() => readReportDetail({ ...detail, availableDecisions: ["DELETE"] })).toThrow();
});

it("preserves the note on conflict and requires reload and a fresh decision before retry", async () => {
  let posts = 0, reads = 0;
  const bodies: unknown[] = [];
  setup("/moderation/reports/report1", "MODERATOR", async (url, init) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/resolve")) {
      posts++; bodies.push(JSON.parse(String(init?.body)));
      if (posts === 1) return Response.json({ detail: "Content changed. Reload it." }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      return Response.json({ ...detail, report: { ...report, status: "RESOLVED", version: 1, resolver: moderator,
        resolvedAt: "2026-09-19T01:00:00Z", resolutionDecision: "HIDE", resolutionNote: "My careful review" }, availableDecisions: [] });
    }
    reads++;
    return Response.json({ ...detail, context: { ...detail.context, reply: { ...detail.context.reply, version: reads === 1 ? 0 : 3 } } });
  });
  await screen.findByLabelText("Decision");
  await userEvent.selectOptions(screen.getByLabelText("Decision"), "HIDE");
  await userEvent.type(screen.getByLabelText("Resolution note"), "My careful review");
  await userEvent.click(screen.getByRole("button", { name: "Resolve report" }));
  await screen.findByText(/Your note is preserved/);
  expect(screen.getByLabelText("Resolution note")).toHaveValue("My careful review");
  expect(screen.getByRole("button", { name: "Resolve report" })).toBeDisabled();
  expect(posts).toBe(1);
  await userEvent.click(screen.getByRole("button", { name: "Reload report context" }));
  await waitFor(() => expect(screen.getByLabelText("Decision")).toBeEnabled());
  expect(screen.getByLabelText("Decision")).toHaveValue("");
  await userEvent.selectOptions(screen.getByLabelText("Decision"), "HIDE");
  await userEvent.click(screen.getByRole("button", { name: "Resolve report" }));
  await screen.findByText("Resolved report");
  expect(bodies).toEqual([
    { decision: "HIDE", resolutionNote: "My careful review", expectedVersion: 0, expectedTargetVersion: 0, expectedQuestionVersion: 2 },
    { decision: "HIDE", resolutionNote: "My careful review", expectedVersion: 0, expectedTargetVersion: 3, expectedQuestionVersion: 2 },
  ]);
  expect(screen.queryByLabelText("Resolution note")).not.toBeInTheDocument();
});

it("validates trimmed note bounds before sending and only offers eligible decisions", async () => {
  const { mock } = setup("/moderation/reports/report1", "MODERATOR", async () => Response.json({ ...detail,
    context: { ...detail.context, reply: { ...detail.context.reply, visibility: "HIDDEN" } }, availableDecisions: ["DISMISS", "ACKNOWLEDGE_HIDDEN"] }));
  await screen.findByLabelText("Decision");
  expect(screen.queryByRole("option", { name: "Hide reported content" })).not.toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText("Decision"), "ACKNOWLEDGE_HIDDEN");
  await userEvent.type(screen.getByLabelText("Resolution note"), " tiny ");
  await userEvent.click(screen.getByRole("button", { name: "Resolve report" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("5 to 2000");
  expect(mock.mock.calls.some(([url]) => url.endsWith("/resolve"))).toBe(false);
});

it("disables duplicate submissions and discards late resolution results after account switch", async () => {
  let finish!: (response: Response) => void;
  let posts = 0;
  const { client } = setup("/moderation/reports/report1", "MODERATOR", async (url) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/resolve")) { posts++; return new Promise((resolve) => { finish = resolve; }); }
    return Response.json(detail);
  });
  await screen.findByLabelText("Decision");
  await userEvent.selectOptions(screen.getByLabelText("Decision"), "DISMISS");
  await userEvent.type(screen.getByLabelText("Resolution note"), "Private decision draft");
  await userEvent.click(screen.getByRole("button", { name: "Resolve report" }));
  await waitFor(() => expect(finish).toBeDefined());
  expect(screen.getByRole("button", { name: "Working..." })).toBeDisabled();
  expect(posts).toBe(1);
  await userEvent.click(screen.getByRole("button", { name: "Switch to member" }));
  await screen.findByRole("heading", { name: "Report review is restricted." });
  await act(async () => finish(Response.json({ ...detail, report: { ...report, status: "RESOLVED" }, availableDecisions: [] })));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryByDisplayValue("Private decision draft")).not.toBeInTheDocument();
});
