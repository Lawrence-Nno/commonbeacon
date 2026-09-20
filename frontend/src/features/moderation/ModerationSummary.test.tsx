import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { ModerationPage } from "./ModerationPage";

const zero = { unansweredQuestions: 0, openReports: 0, publishedArticles: 0 };
function AccountControls() {
  const auth = useAuth();
  return <><button onClick={() => void auth.setSession({ id: "member", displayName: "Member", role: "MEMBER" })}>Switch account</button>
    <button onClick={() => void auth.setSession(null)}>Expire account</button></>;
}
function setup(role: string | null = "MODERATOR", response: (init?: RequestInit) => Promise<Response> = async () => Response.json(zero)) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return role ? Response.json({ id: "operator", displayName: "Morgan", role }) : new Response(null, { status: 401 });
    if (url.endsWith("/summary")) return response(init);
    return Response.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/moderation"]}><AuthProvider><AccountControls /><ModerationPage /></AuthProvider></MemoryRouter></QueryClientProvider>);
  return { client, mock };
}
afterEach(() => vi.unstubAllGlobals());

it.each([null, "MEMBER"])("does not request or render summary counts for %s", async (role) => {
  const { mock } = setup(role);
  await screen.findByText(role ? "Report review is restricted." : "Sign in to review reports.");
  expect(mock.mock.calls.some(([url]) => url.endsWith("/summary"))).toBe(false);
  expect(screen.queryByRole("region", { name: "Operational summary" })).not.toBeInTheDocument();
});
it.each(["MODERATOR", "ADMINISTRATOR"])("shows readable zero counts and existing destinations for %s", async (role) => {
  setup(role);
  const summary = await screen.findByRole("region", { name: "Operational summary" });
  await waitFor(() => expect(within(summary).getAllByRole("definition")).toHaveLength(3));
  expect(within(summary).getAllByRole("definition").map((node) => node.textContent)).toEqual(["0", "0", "0"]);
  expect(within(summary).getByRole("link", { name: "Browse boards" })).toHaveAttribute("href", "/");
  expect(within(summary).getByRole("link", { name: "Review open reports" })).toHaveAttribute("href", "/moderation?status=OPEN&page=0");
  expect(within(summary).getByRole("link", { name: "Browse published articles" })).toHaveAttribute("href", "/knowledge");
});
it("has an explicit loading state before counts arrive", async () => {
  let finish!: (r: Response) => void;
  setup(undefined, () => new Promise((resolve) => { finish = resolve; }));
  await screen.findByText("Loading community overview...");
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
  await act(async () => finish(Response.json({ ...zero, openReports: 3 })));
  expect(await screen.findByText("3", { selector: "dd" })).toBeVisible();
});
it("hides stale counts on backend errors and reloads them after explicit retry", async () => {
  let fail = false;
  const { client } = setup(undefined, async () => fail ? new Response(null, { status: 503 }) : Response.json({ ...zero, unansweredQuestions: 7 }));
  await screen.findByText("7", { selector: "dd" }); fail = true;
  await act(async () => { await client.invalidateQueries({ queryKey: ["moderation", "operator", "summary"] }); });
  await screen.findByText("Could not load the community overview.");
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
  fail = false; await userEvent.click(screen.getByRole("button", { name: "Retry overview" }));
  await screen.findByText("7", { selector: "dd" });
});
it("shows forbidden state without a retry loop", async () => {
  setup(undefined, async () => new Response(null, { status: 403 }));
  await screen.findByText("Your account cannot access the community overview.");
  expect(screen.queryByRole("button", { name: "Retry overview" })).not.toBeInTheDocument();
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
});
it("expires the account when the protected endpoint returns 401", async () => {
  const { client } = setup(undefined, async () => new Response(null, { status: 401 }));
  await screen.findByText("Sign in to review reports.");
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
});
it.each(["Switch account", "Expire account"])("cancels private reads and discards late counts after %s", async (action) => {
  let finish!: (r: Response) => void; let signal: AbortSignal | null | undefined;
  const { client } = setup(undefined, (init) => { signal = init?.signal; return new Promise((resolve) => { finish = resolve; }); });
  await screen.findByText("Loading community overview...");
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: action }));
  await screen.findByText(action === "Switch account" ? "Report review is restricted." : "Sign in to review reports.");
  expect(signal?.aborted).toBe(true);
  await act(async () => finish(Response.json({ unansweredQuestions: 99, openReports: 88, publishedArticles: 77 })));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
});
it.each([{ ...zero, openReports: -1 }, { ...zero, unansweredQuestions: 0.5 }, { openReports: 1 }])("rejects invalid summary counts %j", async (data) => {
  setup(undefined, async () => Response.json(data));
  await screen.findByText("Could not load the community overview.");
  expect(screen.queryAllByRole("definition")).toHaveLength(0);
});
