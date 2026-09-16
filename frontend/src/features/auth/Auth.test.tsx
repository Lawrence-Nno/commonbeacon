import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./AuthProvider";
import { AuthPage } from "./AuthPage";

afterEach(() => vi.unstubAllGlobals());
const member = {
  id: "member-a",
  displayName: "Member A",
  role: "MEMBER" as const,
};
function Probe() {
  const { user, expired, setSession } = useAuth();
  return (
    <>
      <span data-testid="identity">
        {user?.displayName ?? "Guest"}
        {expired ? " expired" : ""}
      </span>
      <button onClick={() => void setSession(member)}>Account A</button>
      <button
        onClick={() =>
          void setSession({
            ...member,
            id: "member-b",
            displayName: "Member B",
          })
        }
      >
        Account B
      </button>
      <button onClick={() => void setSession(null)}>Logout</button>
    </>
  );
}
function setup(form = false) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AuthProvider>
          <Probe />
          {form && <AuthPage mode="register" />}
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
it("preserves registration input, clears passwords, and associates server validation errors", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path.endsWith("/me")) return new Response(null, { status: 401 });
      if (path.endsWith("/csrf"))
        return Response.json({ headerName: "X-CSRF-TOKEN", token: "token" });
      return Response.json(
        {
          detail: "Check the highlighted fields.",
          fieldErrors: { displayName: "Choose another name." },
        },
        {
          status: 400,
          headers: { "Content-Type": "application/problem+json" },
        },
      );
    }),
  );
  setup(true);
  await userEvent.type(
    screen.getByLabelText("Email address"),
    "member@example.test",
  );
  await userEvent.type(screen.getByLabelText("Display name"), "Member");
  await userEvent.type(screen.getByLabelText("Password"), "long-test-password");
  await userEvent.click(screen.getByRole("button", { name: "Create account" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Check the highlighted fields.",
  );
  expect(screen.getByLabelText("Email address")).toHaveValue(
    "member@example.test",
  );
  expect(screen.getByLabelText("Display name")).toHaveValue("Member");
  expect(screen.getByLabelText("Display name")).toHaveAccessibleDescription(
    "Choose another name.",
  );
  expect(screen.getByLabelText("Password")).toHaveValue("");
});
it("registration success asks for sign-in without authenticating the new account", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path.endsWith("/me")) return new Response(null, { status: 401 });
      if (path.endsWith("/csrf"))
        return Response.json({ headerName: "X-CSRF-TOKEN", token: "token" });
      return Response.json(member, { status: 201 });
    }),
  );
  setup(true);
  await userEvent.type(
    screen.getByLabelText("Email address"),
    "member@example.test",
  );
  await userEvent.type(screen.getByLabelText("Display name"), "Member");
  await userEvent.type(screen.getByLabelText("Password"), "long-test-password");
  await userEvent.click(screen.getByRole("button", { name: "Create account" }));
  expect(await screen.findByRole("status")).toHaveTextContent(
    "Your account has been created.",
  );
  expect(screen.getByTestId("identity")).toHaveTextContent("Guest");
});
it("clears private query data on login, account switch, logout, and session expiry", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(null, { status: 401 })),
  );
  const client = setup();
  for (const button of ["Account A", "Account B", "Logout", "Account A"]) {
    client.setQueryData(["private"], { secret: "previous account" });
    await userEvent.click(screen.getByRole("button", { name: button }));
    expect(client.getQueryData(["private"])).toBeUndefined();
  }
  client.setQueryData(["private"], { secret: "current account" });
  await act(async () => {
    window.dispatchEvent(new Event("commonbeacon:session-expired"));
  });
  expect(screen.getByTestId("identity")).toHaveTextContent("Guest expired");
  expect(client.getQueryData(["private"])).toBeUndefined();
});
it("does not let a slow startup session response overwrite a completed login", async () => {
  let finish!: (value: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    ),
  );
  setup();
  await userEvent.click(screen.getByRole("button", { name: "Account A" }));
  await act(async () => {
    finish(new Response(null, { status: 401 }));
  });
  await waitFor(() =>
    expect(screen.getByTestId("identity")).toHaveTextContent("Member A"),
  );
});
