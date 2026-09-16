import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "../../app/App";

const board = {
  id: "board-1",
  slug: "help",
  name: "Product help",
  description: "Find useful answers.",
  archived: true,
  createdAt: "2026-09-16T00:00:00Z",
  version: 2,
};
type Role = "MEMBER" | "MODERATOR" | "ADMINISTRATOR";
function mount(
  path: string,
  role?: Role,
  mutation?: (init: RequestInit) => Response,
) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me"))
      return role
        ? Response.json({ id: "user-a", displayName: "Avery", role })
        : new Response(null, { status: 401 });
    if (url.endsWith("/auth/csrf"))
      return Response.json({
        headerName: "X-CSRF-TOKEN",
        token: "fresh-token",
      });
    if (url === "/api/health") return Response.json({ status: "UP" });
    if (init?.method === "PATCH" || init?.method === "POST")
      return mutation ? mutation(init) : Response.json(board);
    if (url === "/api/v1/boards") return Response.json([board]);
    if (url.endsWith("/board-1")) return Response.json(board);
    return Response.json(
      { detail: "This board could not be found." },
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
  return fetchMock;
}
afterEach(() => vi.unstubAllGlobals());

it("lets an anonymous visitor open an archived board and see its empty state", async () => {
  mount("/");
  await userEvent.click(
    await screen.findByRole("link", { name: /Product help/ }),
  );
  expect(
    await screen.findByRole("heading", { name: "Product help" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Archived board.")).toBeInTheDocument();
  expect(
    screen.getByRole("heading", { name: "The first question is still ahead." }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("link", { name: "Manage boards" }),
  ).not.toBeInTheDocument();
});
it("offers a way home when a board is missing", async () => {
  mount("/boards/missing");
  expect(
    await screen.findByRole("heading", { name: "Board not found." }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: "Back to the community" }),
  ).toHaveAttribute("href", "/");
});
it.each(["MEMBER", "MODERATOR"] as const)(
  "does not show board management to a %s",
  async (role) => {
    mount("/admin/boards", role);
    expect(
      await screen.findByRole("heading", {
        name: "Administrator access required.",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Board name")).not.toBeInTheDocument();
  },
);
it("preserves the administrator draft on conflict and sends its original version with CSRF", async () => {
  const fetchMock = mount("/admin/boards", "ADMINISTRATOR", () =>
    Response.json(
      { detail: "This board changed. Reload it before saving again." },
      { status: 409, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit Product help" }),
  );
  await userEvent.clear(screen.getByLabelText("Board name"));
  await userEvent.type(screen.getByLabelText("Board name"), "My draft");
  await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "This board changed.",
  );
  expect(screen.getByLabelText("Board name")).toHaveValue("My draft");
  const mutation = fetchMock.mock.calls.find(
    (call) => call[1]?.method === "PATCH",
  );
  expect(JSON.parse(mutation![1]!.body as string)).toMatchObject({
    name: "My draft",
    expectedVersion: 2,
    archived: true,
  });
  expect(mutation![1]!.headers).toMatchObject({
    "X-CSRF-TOKEN": "fresh-token",
  });
  await userEvent.click(
    screen.getByRole("button", { name: "Reload latest and discard draft" }),
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Board name")).toHaveValue("Product help"),
  );
});
it("associates board validation feedback with its field without losing form values", async () => {
  mount("/admin/boards", "ADMINISTRATOR", () =>
    Response.json(
      {
        detail: "Check the highlighted fields.",
        fieldErrors: { slug: "Choose a different slug." },
      },
      { status: 400, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  await screen.findByRole("heading", { name: "Create a board" });
  await userEvent.type(screen.getByLabelText("Board name"), "New board");
  await userEvent.type(screen.getByLabelText("Slug"), "new-board");
  await userEvent.type(
    screen.getByLabelText("Description"),
    "A place for useful questions.",
  );
  await userEvent.click(screen.getByRole("button", { name: "Create board" }));
  await screen.findByRole("alert");
  expect(screen.getByLabelText("Slug")).toHaveAccessibleDescription(
    "Choose a different slug.",
  );
  expect(screen.getByLabelText("Board name")).toHaveValue("New board");
});
