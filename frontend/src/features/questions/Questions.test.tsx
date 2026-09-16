import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "../../app/App";
import { readQuestion, readQuestionPage } from "./api";

const board = {
  id: "b1",
  slug: "help",
  name: "Product help",
  description: "Helpful questions.",
  archived: false,
  createdAt: "2026-09-16T00:00:00Z",
  version: 0,
};
const original = {
  id: "q1",
  board,
  title: "How can I get started?",
  body: "<script>alert('text')</script>\nThis is plain text.",
  author: { id: "owner", displayName: "Alex" },
  createdAt: board.createdAt,
  updatedAt: board.createdAt,
  version: 0,
  solved: false,
  acceptedReply: null,
};
type Options = {
  user?: string | null;
  archived?: boolean;
  missing?: boolean;
  failure?: number;
  pagination?: boolean;
};
function setup(path: string, options: Options = {}) {
  let current = {
    ...original,
    board: { ...board, archived: options.archived ?? false },
  };
  let reject = options.failure;
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me"))
      return options.user === null
        ? new Response(null, { status: 401 })
        : Response.json({
            id: options.user ?? "owner",
            displayName: "Alex",
            role: "MEMBER",
          });
    if (url.endsWith("/auth/csrf"))
      return Response.json({
        headerName: "X-CSRF-TOKEN",
        token: "fresh-token",
      });
    if (init?.method === "PATCH" || init?.method === "POST") {
      if (reject)
        return Response.json(
          {
            detail:
              reject === 409
                ? "This question changed. Reload it before saving again."
                : "Check the highlighted fields.",
            fieldErrors:
              reject === 400
                ? { title: "Use at least 5 characters." }
                : undefined,
          },
          {
            status: reject,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      const input = JSON.parse(init.body as string);
      current = {
        ...current,
        title: input.title,
        body: input.body,
        version: current.version + 1,
      };
      return Response.json(current);
    }
    if (url.includes("/replies?"))
      return Response.json({
        items: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      });
    if (url.includes("/questions?")) {
      const page = Number(
        new URL(url, "http://localhost").searchParams.get("page"),
      );
      return Response.json({
        items: options.pagination
          ? [
              {
                ...current,
                boardId: board.id,
                title: "Question on page " + (page + 1),
              },
            ]
          : [],
        page,
        size: 20,
        totalElements: options.pagination ? 21 : 0,
        totalPages: options.pagination ? 2 : 0,
      });
    }
    if (url === "/api/v1/boards/b1") return Response.json(current.board);
    if (url === "/api/v1/questions/q1" && !options.missing)
      return Response.json(current);
    if (url === "/api/health") return Response.json({ status: "UP" });
    return Response.json(
      { detail: "This question could not be found." },
      { status: 404, headers: { "Content-Type": "application/problem+json" } },
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return {
    fetchMock,
    changeServer: () => {
      current = {
        ...current,
        title: "Another saved title",
        body: "New server details.",
        version: 3,
      };
      reject = undefined;
    },
  };
}
afterEach(() => vi.unstubAllGlobals());

it("renders plain text and shows the edit action only to the owner", async () => {
  setup("/questions/q1");
  await screen.findByRole("heading", { name: original.title });
  expect(document.querySelector(".question-body")?.textContent).toBe(
    original.body,
  );
  expect(document.querySelector(".question-body script")).toBeNull();
  expect(
    await screen.findByRole("link", { name: "Edit question" }),
  ).toHaveAttribute("href", "/questions/q1/edit");
});
it("denies another member's edit page", async () => {
  setup("/questions/q1/edit", { user: "someone-else" });
  expect(
    await screen.findByRole("heading", {
      name: "Only the author can edit this question.",
    }),
  ).toBeInTheDocument();
  expect(screen.queryByLabelText("Question title")).not.toBeInTheDocument();
});
it("keeps archived questions readable and disables edits", async () => {
  setup("/questions/q1/edit", { archived: true });
  expect(
    await screen.findByRole("button", { name: "Save question" }),
  ).toBeDisabled();
  expect(screen.getByLabelText("Details")).toHaveValue(original.body);
});
it("shows a missing question without exposing content", async () => {
  setup("/questions/q1", { missing: true });
  expect(
    await screen.findByRole("heading", { name: "Question not found." }),
  ).toBeInTheDocument();
  expect(screen.queryByText(original.body)).not.toBeInTheDocument();
});
it("preserves a stale draft until explicit reload and then uses the new version", async () => {
  const api = setup("/questions/q1/edit", { failure: 409 });
  const title = await screen.findByLabelText("Question title");
  await userEvent.clear(title);
  await userEvent.type(title, "My unsaved draft");
  await userEvent.click(screen.getByRole("button", { name: "Save question" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "This question changed.",
  );
  expect(title).toHaveValue("My unsaved draft");
  const first = api.fetchMock.mock.calls.find(
    (call) => call[1]?.method === "PATCH",
  )!;
  expect(JSON.parse(first[1]!.body as string).expectedVersion).toBe(0);
  expect(first[1]!.headers).toMatchObject({ "X-CSRF-TOKEN": "fresh-token" });
  api.changeServer();
  await userEvent.click(
    screen.getByRole("button", { name: "Reload latest and discard draft" }),
  );
  await waitFor(() => expect(title).toHaveValue("Another saved title"));
  await userEvent.click(screen.getByRole("button", { name: "Save question" }));
  await screen.findByRole("heading", { name: "Another saved title" });
  const mutations = api.fetchMock.mock.calls.filter(
    (call) => call[1]?.method === "PATCH",
  );
  expect(JSON.parse(mutations[1][1]!.body as string).expectedVersion).toBe(3);
});
it("preserves new question values and associates field errors", async () => {
  setup("/boards/b1/questions/new", { failure: 400 });
  await userEvent.type(
    await screen.findByLabelText("Question title"),
    "A useful title",
  );
  await userEvent.type(
    screen.getByLabelText("Details"),
    "Some useful details.",
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Publish question" }),
  );
  await screen.findByRole("alert");
  expect(screen.getByLabelText("Question title")).toHaveAccessibleDescription(
    "Use at least 5 characters.",
  );
  expect(screen.getByLabelText("Details")).toHaveValue("Some useful details.");
});
it("changes question pages through the URL and does not carry previous results", async () => {
  setup("/boards/b1", { user: null, pagination: true });
  await screen.findByRole("link", { name: "Question on page 1" });
  await userEvent.click(screen.getByRole("link", { name: "Next page" }));
  expect(
    await screen.findByRole("link", { name: "Question on page 2" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("link", { name: "Question on page 1" }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Previous page" })).toHaveAttribute(
    "href",
    "/boards/b1?page=0",
  );
});
it("rejects invalid page numbers without making a question request", async () => {
  const api = setup("/boards/b1?page=-1", { user: null });
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "This page number is invalid.",
  );
  expect(
    api.fetchMock.mock.calls.some((call) => call[0].includes("/questions?")),
  ).toBe(false);
});
it("rejects malformed question payloads at the HTTP boundary", () => {
  expect(() =>
    readQuestion({ ...original, author: { email: "private@example.test" } }),
  ).toThrow("unexpected");
  expect(() =>
    readQuestionPage({
      items: [],
      page: 0,
      size: 0,
      totalElements: 0,
      totalPages: 0,
    }),
  ).toThrow("unexpected");
});

it("resets pagination when filtering and preserves the filter between pages", async () => {
  const api = setup("/boards/b1?page=1", { pagination: true });
  await screen.findByRole("link", { name: "Question on page 2" });
  await userEvent.selectOptions(screen.getByLabelText("Show questions"), "solved");
  await screen.findByRole("link", { name: "Question on page 1" });
  expect(api.fetchMock.mock.calls.some((call) => call[0].endsWith("page=0&size=20&status=solved"))).toBe(true);
  await userEvent.click(screen.getByRole("link", { name: "Next page" }));
  await screen.findByRole("link", { name: "Question on page 2" });
  expect(screen.getByRole("link", { name: "Previous page" })).toHaveAttribute("href", "/boards/b1?page=0&status=solved");
});
