import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "../../app/App";
import { readQuestion } from "../questions/api";

const author = { id: "owner", displayName: "Alex" };
const board = { id: "b1", name: "Help", archived: false };
const reply = { id: "r1", questionId: "q1", author: { id: "other", displayName: "Sam" },
  body: "Try these steps. <b>Plain text.</b>", createdAt: "2026-09-16T00:00:00Z", updatedAt: "2026-09-16T00:00:00Z", version: 0 };
const question = { id: "q1", title: "How do I get started?", body: "Help with the setup please.", author, board,
  createdAt: reply.createdAt, updatedAt: reply.updatedAt, version: 0, solved: false, acceptedReply: null };

function setup({ owner = true, solved = false, archived = false, conflict = false, slow = false, empty = false } = {}) {
  let current = { ...question, board: { ...board, archived }, solved, acceptedReply: solved ? reply : null };
  let fail = conflict;
  let release: () => void = () => {};
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return Response.json({ ...author, id: owner ? "owner" : "other", role: "ADMINISTRATOR" });
    if (url.endsWith("/auth/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "token" });
    if (init?.method === "PUT" || init?.method === "DELETE") {
      if (slow) await new Promise<void>((resolve) => { release = resolve; });
      if (fail) return Response.json({ detail: "This question changed. Reload it before changing the solution." }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      const selected = init.method === "PUT";
      current = { ...current, solved: selected, acceptedReply: selected ? reply : null, version: current.version + 1 };
      return Response.json(current);
    }
    if (url.includes("/replies?")) return Response.json({ items: empty ? [] : [reply], page: 0, size: 20, totalElements: empty ? 0 : 1, totalPages: 1 });
    if (url === "/api/v1/questions/q1") return Response.json(current);
    return Response.json({ status: "UP" });
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(["questions", "board", "b1", 0, "all"], "cached");
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/questions/q1"]}><App /></MemoryRouter></QueryClientProvider>);
  return { mock, client, finish: () => release(), resolveConflict: () => { fail = false; current = { ...current, version: 4 }; } };
}
afterEach(() => vi.unstubAllGlobals());

it("selects and clears server-confirmed solutions with versions and refreshes board caches", async () => {
  const { mock, client } = setup();
  const select = await screen.findByRole("button", { name: "Accept as solution" });
  client.setQueryData(["moderation", "owner", "summary"], { unansweredQuestions: 1 });
  client.setQueryData(["questions", "board", "b1", 0, "all"], "cached");
  await userEvent.click(select);
  const panel = (await screen.findByRole("heading", { name: "Accepted answer" })).closest("section")!;
  expect(within(panel).getByText(reply.body)).toBeVisible();
  expect(panel.querySelector("b")).toBeNull();
  expect(client.getQueryState(["questions", "board", "b1", 0, "all"])?.isInvalidated).toBe(true);
  expect(client.getQueryState(["moderation", "owner", "summary"])?.isInvalidated).toBe(true);
  const put = mock.mock.calls.find((call) => call[1]?.method === "PUT")!;
  expect(JSON.parse(put[1]!.body as string)).toEqual({ replyId: "r1", expectedVersion: 0 });
  client.setQueryData(["moderation", "owner", "summary"], { unansweredQuestions: 0 });
  await userEvent.click(screen.getByRole("button", { name: "Clear solution" }));
  await waitFor(() => expect(screen.queryByRole("heading", { name: "Accepted answer" })).not.toBeInTheDocument());
  expect(mock.mock.calls.find((call) => call[1]?.method === "DELETE")?.[0]).toContain("expectedVersion=1");
  expect(client.getQueryState(["moderation", "owner", "summary"])?.isInvalidated).toBe(true);
});

it("keeps the accepted panel available without the selected reply on the current page", async () => {
  setup({ solved: true, empty: true });
  expect(await screen.findByRole("heading", { name: "Accepted answer" })).toBeVisible();
  expect(screen.getByText(reply.body)).toBeVisible();
});

it.each([{ owner: false }, { archived: true }])("hides selection controls for unauthorized or archived views: %o", async (options) => {
  setup({ ...options, solved: true });
  await screen.findByRole("heading", { name: "Accepted answer" });
  expect(screen.queryByRole("button", { name: "Clear solution" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Accept as solution" })).not.toBeInTheDocument();
});

it("prevents duplicate selection while a request is pending", async () => {
  const api = setup({ slow: true });
  const button = await screen.findByRole("button", { name: "Accept as solution" });
  await userEvent.click(button);
  expect(button).toBeDisabled();
  await waitFor(() => expect(api.mock.mock.calls.filter((call) => call[1]?.method === "PUT")).toHaveLength(1));
  expect(screen.queryByRole("heading", { name: "Accepted answer" })).not.toBeInTheDocument();
  api.finish();
  await screen.findByRole("heading", { name: "Accepted answer" });
});

it("requires explicit conflict reload and then uses the refreshed question version", async () => {
  const api = setup({ conflict: true });
  await userEvent.click(await screen.findByRole("button", { name: "Accept as solution" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("changed");
  expect(screen.getByRole("button", { name: "Accept as solution" })).toBeDisabled();
  api.resolveConflict();
  await userEvent.click(screen.getByRole("button", { name: "Reload solution status" }));
  await userEvent.click(screen.getByRole("button", { name: "Accept as solution" }));
  await screen.findByRole("heading", { name: "Accepted answer" });
  const calls = api.mock.mock.calls.filter((call) => call[1]?.method === "PUT");
  expect(JSON.parse(calls[1][1]!.body as string).expectedVersion).toBe(4);
});

it("rejects inconsistent solved states and cross-question accepted reply payloads", () => {
  expect(() => readQuestion({ ...question, solved: true })).toThrow("unexpected");
  expect(() => readQuestion({ ...question, solved: true, acceptedReply: { ...reply, questionId: "q2" } })).toThrow("unexpected");
});
