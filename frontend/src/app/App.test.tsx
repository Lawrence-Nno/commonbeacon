import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "./App";

afterEach(() => vi.unstubAllGlobals());

function renderApp(path = "/") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

it("shows an accessible loading state and disables the repeated request", () => {
  vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
  renderApp();
  expect(screen.getByRole("status")).toHaveTextContent(
    "Checking the connection",
  );
  expect(screen.getByRole("button", { name: "Checking…" })).toBeDisabled();
});

it("renders the real empty state and a healthy connection", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(Response.json({ status: "UP" })),
  );
  renderApp();
  expect(
    await screen.findByRole("heading", { name: "A light is on." }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("heading", {
      name: "The first conversation is still ahead.",
    }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: /ask a question/i }),
  ).not.toBeInTheDocument();
});

it("lets the user recover from an outage with Retry", async () => {
  const fetchMock = vi
    .fn()
    .mockRejectedValueOnce(new TypeError("offline"))
    .mockResolvedValueOnce(Response.json({ status: "UP" }));
  vi.stubGlobal("fetch", fetchMock);
  renderApp();
  expect(
    await screen.findByRole("heading", { name: "Connection interrupted" }),
  ).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Try again" }));
  expect(
    await screen.findByRole("heading", { name: "A light is on." }),
  ).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it("replaces cached success with an error when refreshing fails", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(Response.json({ status: "UP" }))
      .mockRejectedValueOnce(new TypeError("offline")),
  );
  renderApp();
  await screen.findByRole("heading", { name: "A light is on." });
  await userEvent.click(
    screen.getByRole("button", { name: "Check connection" }),
  );
  expect(
    await screen.findByRole("heading", { name: "Connection interrupted" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("heading", { name: "A light is on." }),
  ).not.toBeInTheDocument();
});

it("navigates to the about route", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(Response.json({ status: "UP" })),
  );
  renderApp();
  await userEvent.click(
    screen.getByRole("link", { name: /Get to know CommonBeacon/ }),
  );
  expect(
    screen.getByRole("heading", { name: /Better answers begin/ }),
  ).toBeInTheDocument();
  expect(document.title).toBe("About · CommonBeacon");
});

it("offers a way home from an unknown route", () => {
  renderApp("/missing-page");
  expect(screen.getByText(/We couldn't find that page/)).toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: "Back to the community" }),
  ).toHaveAttribute("href", "/");
});
