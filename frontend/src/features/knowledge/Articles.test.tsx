import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { AdminArticles } from "./AdminArticles";
import { KnowledgePage, KnowledgeArticlePage } from "./KnowledgePage";
import { readAdminArticle, readArticle } from "./api";
import type { AdminArticle } from "./api";

const user = { id: "admin", displayName: "Avery", role: "ADMINISTRATOR" };
const article: AdminArticle = { id: "a1", slug: "setup-guide", title: "Private setup draft", body: "Private <script>article text</script>",
  author: { id: "admin", displayName: "Avery" }, status: "DRAFT", createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z", publishedAt: null, version: 0 };
const published: AdminArticle = { ...article, status: "PUBLISHED", publishedAt: "2026-09-20T01:00:00Z", version: 1 };
const page = { items: [article], page: 0, size: 20, totalElements: 21, totalPages: 2 };
function Accounts() {
  const auth = useAuth();
  return <><button onClick={() => void auth.setSession({ id: "member", displayName: "Sam", role: "MEMBER" })}>Switch account</button>
    <button onClick={() => void auth.setSession(null)}>Expire account</button></>;
}
function setup(route = "/admin/articles/a1", role: string | null = "ADMINISTRATOR", responder?: (url: string, init?: RequestInit) => Promise<Response>) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return role ? Response.json({ ...user, role }) : new Response(null, { status: 401 });
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    return responder ? responder(url, init) : Response.json(url.includes("?") ? page : article);
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider><Accounts /><Routes>
    <Route path="/admin/articles" element={<AdminArticles />} /><Route path="/admin/articles/new" element={<AdminArticles creating />} />
    <Route path="/admin/articles/:articleId" element={<AdminArticles />} /><Route path="/knowledge" element={<KnowledgePage />} />
    <Route path="/knowledge/:slug" element={<KnowledgeArticlePage />} />
  </Routes></AuthProvider></MemoryRouter></QueryClientProvider>);
  return { client, mock };
}
afterEach(() => vi.unstubAllGlobals());

it.each([null, "MEMBER", "MODERATOR"])("blocks %s from fetching drafts", async (role) => {
  const { mock } = setup(undefined, role);
  await screen.findByText(role ? "Article administration is restricted." : "Sign in to manage articles.");
  expect(mock.mock.calls.every(([url]) => url.endsWith("/auth/me"))).toBe(true);
});

it("filters and pages administrator lists and resets invalid filters", async () => {
  const { mock } = setup("/admin/articles?page=-1");
  await screen.findByText(/Invalid article filters/);
  await userEvent.click(screen.getByRole("button", { name: "Reset article filters" }));
  await screen.findByRole("link", { name: article.title });
  await userEvent.click(screen.getByRole("button", { name: "Next articles" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("page=1"), expect.anything()));
  await userEvent.selectOptions(screen.getByLabelText("Article status"), "ARCHIVED");
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("page=0&size=20&status=ARCHIVED"), expect.anything()));
});

it("creates a trimmed draft and keeps the slug immutable afterwards", async () => {
  let sent: unknown;
  setup("/admin/articles/new", "ADMINISTRATOR", async (_url, init) => {
    if (init?.method === "POST") sent = JSON.parse(String(init.body));
    return Response.json(article);
  });
  await screen.findByLabelText("Article slug");
  await userEvent.type(screen.getByLabelText("Article slug"), " setup-guide ");
  await userEvent.type(screen.getByLabelText("Article title"), " Private setup draft ");
  await userEvent.type(screen.getByLabelText("Article body"), " A new article body. ");
  await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
  await screen.findByRole("heading", { name: "Edit article" });
  expect(sent).toEqual({ slug: "setup-guide", title: "Private setup draft", body: "A new article body." });
  expect(await screen.findByLabelText("Article slug")).toHaveAttribute("readonly");
});

it.each([409, 500])("preserves a draft after HTTP %s and explicitly reconciles to the latest version", async (status) => {
  let posts = 0, reads = 0; const bodies: unknown[] = [];
  setup(undefined, "ADMINISTRATOR", async (_url, init) => {
    if (init?.method === "PATCH") {
      posts++; bodies.push(JSON.parse(String(init.body)));
      return posts === 1 ? Response.json({ detail: "Reload and review this article." }, { status, headers: { "Content-Type": "application/problem+json" } }) : Response.json({ ...article, body: "My preserved draft body", version: 4 });
    }
    reads++; return Response.json(reads === 1 ? article : { ...article, title: "Server title changed", body: "Server copy body changed", version: 3 });
  });
  await screen.findByLabelText("Article body");
  await userEvent.clear(screen.getByLabelText("Article body")); await userEvent.type(screen.getByLabelText("Article body"), "My preserved draft body");
  expect(screen.getByRole("button", { name: "Publish article" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Save article" }));
  await screen.findByText(/Your draft is preserved/);
  expect(screen.getByLabelText("Article body")).toHaveValue("My preserved draft body");
  expect(posts).toBe(1); expect(screen.getByRole("button", { name: "Save article" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Load latest article" }));
  await screen.findByText("Server copy body changed");
  expect(screen.getByLabelText("Article body")).toHaveValue("My preserved draft body");
  await userEvent.click(screen.getByRole("button", { name: "Keep my draft after review" }));
  await userEvent.click(screen.getByRole("button", { name: "Save article" }));
  await screen.findByText("Article saved.");
  expect(bodies).toEqual([{ title: article.title, body: "My preserved draft body", expectedVersion: 0 }, { title: article.title, body: "My preserved draft body", expectedVersion: 3 }]);
});

it("waits for publication confirmation, warns about live edits, and archives to read-only", async () => {
  let finish!: (r: Response) => void; let current = article;
  const { client } = setup(undefined, "ADMINISTRATOR", async (url, init) => {
    if (url.endsWith("/publish")) return new Promise((resolve) => { finish = resolve; });
    if (url.endsWith("/archive")) { expect(JSON.parse(String(init?.body))).toEqual({ expectedVersion: 1 }); current = { ...published, status: "ARCHIVED" }; }
    return Response.json(current);
  });
  await screen.findByLabelText("Article body");
  client.setQueryDefaults(["articles"], { gcTime: Infinity });
  client.setQueryData(["articles", "list", 0], { ...page, items: [] });
  await userEvent.click(screen.getByRole("button", { name: "Publish article" }));
  await waitFor(() => expect(finish).toBeDefined());
  expect(screen.getByRole("button", { name: "Publish article" })).toBeDisabled();
  expect(screen.queryByText(/Saving changes updates/)).not.toBeInTheDocument();
  current = published;
  await act(async () => finish(Response.json(published)));
  await screen.findByText(/Saving changes updates the public article immediately/);
  expect(client.getQueryState(["articles", "list", 0])?.isInvalidated).toBe(true);
  await userEvent.click(screen.getByRole("button", { name: "Archive article" }));
  await screen.findByText(/archived and read-only/);
  expect(screen.getByLabelText("Article body")).toHaveAttribute("readonly");
  expect(screen.queryByRole("button", { name: "Save article" })).not.toBeInTheDocument();
});

it("validates fields locally and preserves server validation details", async () => {
  const { mock } = setup("/admin/articles/new", "ADMINISTRATOR", async () => Response.json({ detail: "Check fields", fieldErrors: { slug: "Already taken" } }, { status: 400, headers: { "Content-Type": "application/problem+json" } }));
  await screen.findByLabelText("Article slug");
  await userEvent.type(screen.getByLabelText("Article slug"), "BAD");
  await userEvent.type(screen.getByLabelText("Article title"), "tiny");
  await userEvent.type(screen.getByLabelText("Article body"), "too short");
  await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("lowercase");
  expect(mock.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false);
  for (const [label, value] of [["Article slug", "valid-slug"], ["Article title", "Valid title"], ["Article body", "Valid article body"]]) {
    await userEvent.clear(screen.getByLabelText(label)); await userEvent.type(screen.getByLabelText(label), value);
  }
  await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
  await screen.findByText("slug: Already taken");
  expect(screen.getByLabelText("Article body")).toHaveValue("Valid article body");
});

it("cancels a private read and ignores late data after account switching", async () => {
  let finish!: (r: Response) => void; let signal: AbortSignal | null | undefined;
  const { client } = setup(undefined, "ADMINISTRATOR", async (_url, init) => { signal = init?.signal; return new Promise((resolve) => { finish = resolve; }); });
  await screen.findByText("Loading article editor..."); await waitFor(() => expect(finish).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: "Switch account" }));
  await screen.findByText("Article administration is restricted."); expect(signal?.aborted).toBe(true);
  await act(async () => finish(Response.json(article)));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryByDisplayValue(article.body)).not.toBeInTheDocument();
});

it("discards a pending save and loaded draft on expiry without repopulating caches", async () => {
  let finish!: (r: Response) => void;
  const { client } = setup(undefined, "ADMINISTRATOR", async (_url, init) => init?.method === "PATCH" ? new Promise((resolve) => { finish = resolve; }) : Response.json(article));
  await screen.findByLabelText("Article body"); await userEvent.click(screen.getByRole("button", { name: "Save article" }));
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: "Expire account" })); await screen.findByText("Sign in to manage articles.");
  await act(async () => finish(Response.json({ ...article, version: 1 })));
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(screen.queryByDisplayValue(article.body)).not.toBeInTheDocument();
});

it("serves public pagination and renders article markup as text", async () => {
  setup("/knowledge", null, async (url) => Response.json(url.includes("?") ? { ...page, items: [published] } : published));
  await userEvent.click(await screen.findByRole("link", { name: article.title }));
  expect(await screen.findByText(article.body)).toBeVisible(); expect(document.querySelector("script")).toBeNull();
  expect(screen.queryByText("DRAFT")).not.toBeInTheDocument();
});
it.each([404, 500])("hides stale public content on HTTP %s", async (status) => {
  setup("/knowledge/setup-guide", null, async () => Response.json({ detail: "Unavailable" }, { status, headers: { "Content-Type": "application/problem+json" } }));
  await screen.findByRole("alert"); expect(screen.queryByText(article.body)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Retry articles" }) !== null).toBe(status === 500);
});
it("shows an empty public library and rejects invalid response shapes", async () => {
  setup("/knowledge", null, async () => Response.json({ ...page, items: [], totalElements: 0, totalPages: 0 }));
  await screen.findByText("No published articles yet.");
  expect(() => readArticle(article)).toThrow();
  expect(() => readAdminArticle({ ...article, status: "DELETED" })).toThrow();
  expect(() => readAdminArticle({ ...article, version: -1 })).toThrow();
});

it("uses an archived server copy without allowing an obsolete draft to be submitted", async () => {
  let reads = 0;
  setup(undefined, "ADMINISTRATOR", async () => Response.json(++reads === 1 ? article : { ...article, status: "ARCHIVED", body: "Archived server article body", version: 2 }));
  await screen.findByLabelText("Article body");
  await userEvent.clear(screen.getByLabelText("Article body"));
  await userEvent.type(screen.getByLabelText("Article body"), "My unsaved private text");
  await userEvent.click(screen.getByRole("button", { name: "Load latest article" }));
  await screen.findByText("Archived server article body");
  expect(screen.getByLabelText("Article body")).toHaveValue("My unsaved private text");
  expect(screen.queryByRole("button", { name: "Keep my draft after review" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Save article" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Use server copy" }));
  expect(screen.getByLabelText("Article body")).toHaveValue("Archived server article body");
  expect(screen.getByLabelText("Article body")).toHaveAttribute("readonly");
  expect(screen.queryByRole("button", { name: "Save article" })).not.toBeInTheDocument();
});

it("removes an already-rendered public article when a refresh returns 404", async () => {
  let unavailable = false;
  const { client } = setup("/knowledge/setup-guide", null, async () => unavailable ? new Response(null, { status: 404 }) : Response.json(published));
  await screen.findByText(article.body);
  unavailable = true;
  await act(async () => { await client.invalidateQueries({ queryKey: ["articles"] }); });
  expect(await screen.findByRole("alert")).toHaveTextContent("Article not found");
  expect(screen.queryByText(article.body)).not.toBeInTheDocument();
});

it("keeps public page navigation bounded and resets invalid page input", async () => {
  const { mock } = setup("/knowledge?page=-1", null, async (url) => Response.json({ ...page, items: [published], page: url.includes("page=1") ? 1 : 0 }));
  await userEvent.click(await screen.findByRole("button", { name: "Reset article page" }));
  await screen.findByRole("link", { name: article.title });
  expect(screen.getByRole("button", { name: "Previous articles" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Next articles" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("page=1&size=20"), expect.anything()));
  await screen.findByRole("link", { name: article.title });
  expect(screen.getByRole("button", { name: "Next articles" })).toBeDisabled();
});
