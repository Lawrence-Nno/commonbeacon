import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { Erasure, ErasureReceipt } from "./Erasure";

const id = "00000000-0000-0000-0000-000000000001";
const member = { id: "member", displayName: "Person", role: "MEMBER" };
const preview = { id, instanceId: id, digest: "a".repeat(64), phrase: "DELETE MY ACCOUNT", expiresAt: "2099-01-01T00:00:00Z", backupRetentionDays: 30, ownQuestions: 2, ownReplies: 3, counts: { own_reports: 1 } };
function Accounts() { const { setSession } = useAuth();return <button onClick={() => void setSession({ ...member, id: "other", role: "MEMBER" })}>Switch account</button>; }
function setup(responder?: (url: string, init?: RequestInit) => Promise<Response>, company = false, receipt = false) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return Response.json(member);
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/reauthentication")) return Response.json({ token: "g".repeat(43), expiresAt: "2099-01-01T00:00:00Z" });
    if (responder) return responder(url, init);
    return Response.json(url.endsWith("/previews") ? preview : { id, state: "COMPLETED", processed: 4, retrying: false });
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter><AuthProvider><Accounts />{receipt ? <ErasureReceipt /> : <Erasure company={company} />}</AuthProvider></MemoryRouter></QueryClientProvider>);
  return mock;
}
async function inspect() { await userEvent.click(await screen.findByRole("button", { name: "Review erasure impact" }));await screen.findByRole("heading", { name: "Review before erasure" }); }
async function acknowledge() {
  await inspect();for (const box of screen.getAllByRole("checkbox")) await userEvent.click(box);
  await userEvent.type(screen.getByLabelText(/Type exactly/), preview.phrase);
  await userEvent.click(screen.getByRole("button", { name: "Confirm password and erase" }));
  await userEvent.type(screen.getByLabelText("Current password"), "private-password");await userEvent.click(screen.getByRole("button", { name: "Confirm password" }));
}
afterEach(() => { vi.unstubAllGlobals();vi.restoreAllMocks(); });
it("requires every acknowledgement and exact phrase then uses a scoped grant and private receipt", async () => {
  const mock = setup();await inspect();expect(screen.getByRole("button", { name: "Confirm password and erase" })).toBeDisabled();
  for (const box of screen.getAllByRole("checkbox")) await userEvent.click(box);
  await userEvent.type(screen.getByLabelText(/Type exactly/), "DELETE");expect(screen.getByRole("button", { name: "Confirm password and erase" })).toBeDisabled();
  await userEvent.clear(screen.getByLabelText(/Type exactly/));await userEvent.type(screen.getByLabelText(/Type exactly/), preview.phrase);
  await userEvent.click(screen.getByRole("button", { name: "Confirm password and erase" }));await userEvent.type(screen.getByLabelText("Current password"), "private-password");await userEvent.click(screen.getByRole("button", { name: "Confirm password" }));
  await screen.findByText("Erasure completed for the disclosed scope.");
  const confirmation = mock.mock.calls.find(([url]) => url.endsWith("/confirm"))!;const body = JSON.parse(confirmation[1]!.body as string);
  expect(body.digest).toBe(preview.digest);expect(body.acknowledgements).toHaveLength(4);expect(body.receiptToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(JSON.parse(mock.mock.calls.find(([url]) => url.endsWith("/reauthentication"))![1]!.body as string).scope).toBe("ACCOUNT_ERASURE");
  expect(mock.mock.calls.find(([url]) => url.includes("/receipts/"))![1]!.headers).toEqual({ "X-Erasure-Receipt": body.receiptToken });
  expect(screen.queryByText(body.receiptToken)).not.toBeInTheDocument();expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
});
it("checks the receipt after a lost response and never resubmits erasure", async () => {
  const mock = setup(async url => {if (url.endsWith("/confirm")) throw new TypeError("lost response");return Response.json(url.endsWith("/previews") ? preview : { id, state: "COMPLETED", processed: 5, retrying: false });});
  await acknowledge();await screen.findByText("Erasure completed for the disclosed scope.");expect(mock.mock.calls.filter(([url]) => url.endsWith("/confirm"))).toHaveLength(1);
});
it("clears consent after a stale preview without automatically trying again", async () => {
  const mock = setup(async url => url.endsWith("/confirm") ? Response.json({ detail: "Review a fresh preview.", code: "STALE_ERASURE_PREVIEW" }, { status: 409, headers: { "Content-Type": "application/problem+json" } }) : Response.json(preview));
  await acknowledge();await screen.findByRole("alert");expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();expect(mock.mock.calls.filter(([url]) => url.endsWith("/confirm"))).toHaveLength(1);
});
it("drops a late private preview after switching accounts", async () => {
  let resolve!: (response: Response) => void;setup(() => new Promise(r => { resolve = r; }));
  await userEvent.click(await screen.findByRole("button", { name: "Review erasure impact" }));await waitFor(() => expect(resolve).toBeDefined());await userEvent.click(screen.getByText("Switch account"));
  await act(async () => resolve(Response.json(preview)));expect(screen.queryByRole("heading", { name: "Review before erasure" })).not.toBeInTheDocument();
});
it("prevents members from viewing company controls", async () => {const mock = setup(undefined, true);await screen.findByText("Company erasure requires an administrator.");expect(mock.mock.calls.some(([url]) => url.endsWith("/previews"))).toBe(false);});
it("restores status from a manually supplied capability without authorizing erasure", async () => {
  const mock = setup(undefined, false, true);await userEvent.type(screen.getByLabelText("Receipt ID"), id);await userEvent.type(screen.getByLabelText("Receipt token"), "t".repeat(43));await userEvent.click(screen.getByText("Check erasure status"));await screen.findByText("Erasure completed for the disclosed scope.");expect(mock.mock.calls.some(([url]) => url.endsWith("/confirm"))).toBe(false);
});
