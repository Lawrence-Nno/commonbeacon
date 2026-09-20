import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { SearchPage } from "./SearchPage";
import { readSearchPage } from "./api";

const id = "00000000-0000-0000-0000-000000000008";
const article = { kind: "ARTICLE", id, title: "Set up <script>plain title</script>", snippet: "<img src=x onerror=alert(1)> is plain text", url: "/knowledge/setup", rank: 0 };
const question = { ...article, kind: "QUESTION", title: "Setup community question", url: `/questions/${id}` };
const page = { items: [article, question], page: 0, size: 20, totalElements: 21, totalPages: 2 };
function Back() { const navigate = useNavigate(); return <button onClick={() => navigate(-1)}>Go back</button>; }
function setup(route = "/search?q=setup", responder?: (url: string, init?: RequestInit) => Promise<Response>) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => responder ? responder(url, init) : Response.json(page));
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><Back /><Routes>
    <Route path="/search" element={<SearchPage />} /><Route path="/knowledge/setup" element={<h1>Article detail</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { client, mock };
}
afterEach(() => vi.unstubAllGlobals());

it("shows both result kinds, literal text and safe detail links", async () => {
  setup();
  expect(await screen.findByRole("link", { name: article.title })).toHaveAttribute("href", article.url);
  expect(screen.getByRole("link", { name: question.title })).toHaveAttribute("href", question.url);
  expect(screen.getAllByText(article.snippet)).toHaveLength(2);
  expect(document.querySelector("script, img")).toBeNull();
  expect(screen.getByText(/English stemming is applied/)).toBeVisible();
  await userEvent.click(screen.getByRole("link", { name: article.title }));
  expect(await screen.findByRole("heading", { name: "Article detail" })).toBeVisible();
});

it("keeps query/page in navigation history and resets page for a new query", async () => {
  const { mock } = setup(undefined, async (url) => Response.json({ ...page, page: Number(new URL(url, "http://local").searchParams.get("page")) }));
  await screen.findByRole("link", { name: article.title });
  await userEvent.click(screen.getByRole("button", { name: "Next results" }));
  await screen.findByText("Page 2 of 2");
  expect(screen.getByRole("button", { name: "Next results" })).toBeDisabled();
  await userEvent.clear(screen.getByLabelText("Search titles and bodies"));
  await userEvent.type(screen.getByLabelText("Search titles and bodies"), "  50%_off!  ");
  await userEvent.click(screen.getByRole("button", { name: "Search" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith("/api/v1/search?q=50%25_off%21&page=0&size=20", expect.anything()));
  await screen.findByText("Page 1 of 2");
  expect(screen.getByLabelText("Search titles and bodies")).toHaveValue("50%_off!");
  await userEvent.click(screen.getByRole("button", { name: "Go back" }));
  expect(await screen.findByDisplayValue("setup")).toBeVisible();
  await screen.findByText("Page 2 of 2");
});

it("prompts for blank queries without requesting every public record", async () => {
  const { mock } = setup("/search?q=%20%20");
  expect(screen.getByText(/Enter words to search/)).toBeVisible();
  expect(mock).not.toHaveBeenCalled();
});

it.each(["page=-1", "page=9007199254740991", "q=" + "x".repeat(201)])("rejects invalid route input %s", async (parameter) => {
  const { mock } = setup(`/search?${parameter}`);
  expect(screen.getByRole("alert")).toBeVisible(); expect(mock).not.toHaveBeenCalled();
});

it("cancels the older query and ignores its late response", async () => {
  let finish!: (response: Response) => void; let oldSignal: AbortSignal | null | undefined;
  setup("/search?q=older", async (url, init) => {
    if (url.includes("q=older")) { oldSignal = init?.signal; return new Promise((resolve) => { finish = resolve; }); }
    return Response.json({ ...page, items: [{ ...article, title: "Newer result" }] });
  });
  await screen.findByText("Searching...");
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.clear(screen.getByLabelText("Search titles and bodies")); await userEvent.type(screen.getByLabelText("Search titles and bodies"), "newer");
  await userEvent.click(screen.getByRole("button", { name: "Search" }));
  await screen.findByRole("link", { name: "Newer result" }); expect(oldSignal?.aborted).toBe(true);
  await act(async () => finish(Response.json(page)));
  expect(screen.queryByRole("link", { name: article.title })).not.toBeInTheDocument();
  expect(screen.getByLabelText("Search titles and bodies")).toHaveValue("newer");
});

it("clears stale hits on refresh errors and supports retry", async () => {
  let fail = false;
  const { client } = setup(undefined, async () => fail ? Response.json({ detail: "Search unavailable" }, { status: 503, headers: { "Content-Type": "application/problem+json" } }) : Response.json(page));
  await screen.findByRole("link", { name: article.title }); fail = true;
  await act(async () => { await client.invalidateQueries({ queryKey: ["search"] }); });
  expect(await screen.findByRole("alert")).toHaveTextContent("Search unavailable");
  expect(screen.queryByRole("link", { name: article.title })).not.toBeInTheDocument();
  expect(screen.getByLabelText("Search titles and bodies")).toHaveValue("setup");
  fail = false; await userEvent.click(screen.getByRole("button", { name: "Retry search" }));
  await screen.findByRole("link", { name: article.title });
});

it("shows server query validation without a retry loop", async () => {
  setup(undefined, async () => Response.json({ detail: "Invalid query", fieldErrors: { q: "Use fewer characters" } }, { status: 400, headers: { "Content-Type": "application/problem+json" } }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Use fewer characters");
  expect(screen.queryByRole("button", { name: "Retry search" })).not.toBeInTheDocument();
});

it("offers first-page recovery for an empty out-of-range page", async () => {
  const { mock } = setup("/search?q=missing&page=4", async () => Response.json({ ...page, items: [], totalElements: 0, totalPages: 0 }));
  await screen.findByText("No matching results on this page.");
  await userEvent.click(screen.getByRole("button", { name: "First page" }));
  await waitFor(() => expect(mock).toHaveBeenCalledWith(expect.stringContaining("page=0"), expect.anything()));
});

it("rejects malformed hits and unsafe or mismatched URLs", () => {
  for (const patch of [{ kind: "REPLY" }, { rank: -1 }, { rank: NaN }, { url: "https://outside.test" },
    { url: "//outside.test" }, { url: "/knowledge/../admin" }, { snippet: "x".repeat(241) }, { id: "bad" }]) {
    expect(() => readSearchPage({ ...page, items: [{ ...article, ...patch }] })).toThrow();
  }
  expect(() => readSearchPage({ ...page, items: [{ ...question, url: "/knowledge/setup" }] })).toThrow();
  expect(() => readSearchPage({ ...page, size: 101 })).toThrow();
});
