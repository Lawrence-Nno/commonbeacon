import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { ModerationContentPage } from "./ModerationContentPage";
import { readActionPage, readContentContext } from "./reviewApi";

const moderator = { id: "mod", displayName: "Morgan", role: "MODERATOR" as const };
const actor = { id: "member", displayName: "Alex" };
const report = { id: "report1", status: "OPEN", reason: "Private report reason", reporter: actor,
  targetKind: "REPLY", targetId: "r1", questionId: "q1", createdAt: "2026-09-19T00:00:00Z",
  updatedAt: "2026-09-19T00:00:00Z", version: 0, resolvedAt: null, resolver: null, resolutionDecision: null, resolutionNote: null };
const detail = { report, context: { board: { id: "b1", name: "Help", archived: true },
  question: { id: "q1", title: "Hidden question title", body: "Hidden <script>plain text</script>", author: actor, visibility: "HIDDEN", version: 2, acceptedReplyId: "r1" },
  reply: { id: "r1", body: "Reported reply text", author: actor, visibility: "VISIBLE", version: 0 },
  targetKind: "REPLY", targetId: "r1", effectivePublicVisibility: false }, availableDecisions: ["DISMISS", "HIDE"] };
const hiddenContext = { ...detail.context, reply: { ...detail.context.reply, visibility: "HIDDEN", version: 1 } };
const history = { items: [{ id: "a1", actor: moderator, targetKind: "REPLY", targetId: "r1", action: "HIDE",
  reason: "Private audit reason <script>", createdAt: "2026-09-19T00:00:00Z" }], page: 0, size: 20, totalElements: 21, totalPages: 2 };
const restored = { ...hiddenContext, reply: { ...hiddenContext.reply, visibility: "VISIBLE", version: 2 } };

function SwitchAccount() {
  const auth = useAuth();
  return <><button onClick={() => void auth.setSession({ ...actor, role: "MEMBER" })}>Switch to member</button>
    <button onClick={() => void auth.setSession(null)}>Expire session</button></>;
}
function setup(route = "/moderation/replies/r1", role: string | null = "MODERATOR", responder?: (url: string, init?: RequestInit) => Promise<Response>) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return role === null ? new Response(null, { status: 401 }) : Response.json({ ...moderator, role });
    if (responder) return responder(url, init);
    return Response.json(url.includes("/actions") ? history : hiddenContext);
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider>
    <SwitchAccount /><Routes><Route path="/moderation/replies/:contentId" element={<ModerationContentPage reply />} />
      <Route path="/moderation/questions/:contentId" element={<ModerationContentPage reply={false} />} /></Routes>
  </AuthProvider></MemoryRouter></QueryClientProvider>);
  return { client, mock };
}
afterEach(() => vi.unstubAllGlobals());

it.each(["MEMBER", null])("blocks %s before fetching private context/history", async (role) => {
  const { mock } = setup("/moderation/replies/r1", role);
  await screen.findByRole("heading", { name: role ? "Content review is restricted." : "Sign in to review content." });
  expect(mock.mock.calls.every(([url]) => url.endsWith("/auth/me"))).toBe(true);
});

it.each(["MODERATOR", "ADMINISTRATOR"])("lets %s review private history and independently navigate the parent", async (role) => {
  const { mock } = setup("/moderation/replies/r1", role);
  await screen.findByText("Private audit reason <script>");
  expect(document.querySelector("script")).toBeNull();
  expect(screen.getByText(/remain private until its parent is restored/)).toBeVisible();
  expect(screen.getByRole("link", { name: "Question history and restoration" })).toHaveAttribute("href", "/moderation/questions/q1");
  await userEvent.click(screen.getByRole("button", { name: "Next actions" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("actions?page=1&size=20"), expect.anything()));
});

it("sends reviewed versions, waits for confirmation and keeps a restored child private beneath its hidden parent", async () => {
  let finish!: (value: Response) => void;
  let body: unknown;
  const { mock } = setup("/moderation/replies/r1", "MODERATOR", async (url, init) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/restore")) { body = JSON.parse(String(init?.body)); return new Promise((resolve) => { finish = resolve; }); }
    return Response.json(url.includes("/actions") ? history : hiddenContext);
  });
  await screen.findByLabelText("Restoration reason");
  await userEvent.type(screen.getByLabelText("Restoration reason"), "  Reviewed safe content  ");
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  await waitFor(() => expect(finish).toBeDefined());
  expect(screen.getByRole("button", { name: "Working..." })).toBeDisabled();
  expect(screen.queryByText(/No restoration is needed/)).not.toBeInTheDocument();
  expect(body).toEqual({ reason: "Reviewed safe content", expectedTargetVersion: 1, expectedQuestionVersion: 2 });
  await act(async () => finish(Response.json(restored)));
  await screen.findByText(/No restoration is needed/);
  expect(screen.getByText(/hidden question keeps it private/)).toBeVisible();
  expect(screen.queryByRole("button", { name: "Restore content" })).not.toBeInTheDocument();
  expect(mock.mock.calls.filter(([url]) => url.endsWith("/restore"))).toHaveLength(1);
});

it.each([409, 500])("preserves the reason after HTTP %s and requires explicit reload before retry", async (status) => {
  let reads = 0; const bodies: unknown[] = [];
  setup("/moderation/replies/r1", "MODERATOR", async (url, init) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.includes("/actions")) return Response.json(history);
    if (url.endsWith("/restore")) {
      bodies.push(JSON.parse(String(init?.body)));
      return bodies.length === 1 ? Response.json({ detail: "Reload before retrying." }, { status, headers: { "Content-Type": "application/problem+json" } }) : Response.json(restored);
    }
    reads++;
    return Response.json({ ...hiddenContext, reply: { ...hiddenContext.reply, version: reads === 1 ? 1 : 3 } });
  });
  await screen.findByLabelText("Restoration reason");
  await userEvent.type(screen.getByLabelText("Restoration reason"), "Keep my review note");
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  await screen.findByText(/Your reason is preserved/);
  expect(screen.getByLabelText("Restoration reason")).toHaveValue("Keep my review note");
  expect(screen.getByRole("button", { name: "Restore content" })).toBeDisabled();
  expect(bodies).toHaveLength(1);
  await userEvent.click(screen.getByRole("button", { name: "Reload content context" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Restore content" })).toBeEnabled());
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  await screen.findByText(/No restoration is needed/);
  expect(bodies[1]).toEqual({ reason: "Keep my review note", expectedTargetVersion: 3, expectedQuestionVersion: 2 });
});

it("validates trimmed reasons and sends no parent version for question restoration", async () => {
  let body: unknown;
  const parent = { ...hiddenContext, targetKind: "QUESTION", targetId: "q1", reply: null };
  const { mock } = setup("/moderation/questions/q1", "MODERATOR", async (url, init) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/restore")) { body = JSON.parse(String(init?.body)); return Response.json({ ...parent, question: { ...parent.question, visibility: "VISIBLE", version: 3 }, effectivePublicVisibility: true }); }
    return Response.json(url.includes("/actions") ? { ...history, items: [], totalElements: 0, totalPages: 0 } : parent);
  });
  await screen.findByLabelText("Restoration reason");
  await userEvent.type(screen.getByLabelText("Restoration reason"), " tiny ");
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("5 to 2000");
  expect(mock.mock.calls.some(([url]) => url.endsWith("/restore"))).toBe(false);
  await userEvent.clear(screen.getByLabelText("Restoration reason"));
  await userEvent.type(screen.getByLabelText("Restoration reason"), "Safe parent content");
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  await screen.findByText(/No restoration is needed/);
  expect(body).toEqual({ reason: "Safe parent content", expectedTargetVersion: 2 });
});

it("discards a late mutation and private history/draft after account switching", async () => {
  let finish!: (value: Response) => void;
  const { client } = setup("/moderation/replies/r1", "MODERATOR", async (url) => {
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/restore")) return new Promise((resolve) => { finish = resolve; });
    return Response.json(url.includes("/actions") ? history : hiddenContext);
  });
  await screen.findByLabelText("Restoration reason");
  await userEvent.type(screen.getByLabelText("Restoration reason"), "Private restore draft");
  await userEvent.click(screen.getByRole("button", { name: "Restore content" }));
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: "Switch to member" }));
  await screen.findByText("Content review is restricted.");
  await act(async () => finish(Response.json(restored)));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryByDisplayValue("Private restore draft")).not.toBeInTheDocument();
  expect(screen.queryByText("Private audit reason <script>")).not.toBeInTheDocument();
});

it.each([403, 404, 500])("handles unavailable content HTTP %s without rendering private content", async (status) => {
  setup("/moderation/replies/r1", "MODERATOR", async () => Response.json({ detail: "Content unavailable" }, { status, headers: { "Content-Type": "application/problem+json" } }));
  await screen.findByRole("alert");
  expect(screen.queryByText("Reported reply text")).not.toBeInTheDocument();
});

it("rejects malformed history and context responses", () => {
  expect(() => readActionPage({ ...history, items: [{ ...history.items[0], action: "DELETE" }] })).toThrow();
  expect(() => readActionPage({ ...history, size: 101 })).toThrow();
  expect(() => readContentContext({ ...hiddenContext, effectivePublicVisibility: "false" })).toThrow();
});
