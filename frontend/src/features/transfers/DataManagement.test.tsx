import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { DataManagement } from "./DataManagement";
import type { Job } from "./api";

const admin = { id: "admin", displayName: "Avery", role: "ADMINISTRATOR" as const };
const job: Job = { id: "00000000-0000-0000-0000-000000000001", kind: "COMPANY_EXPORT", state: "QUEUED", version: 0,
  createdAt: "2026-09-22T00:00:00Z", updatedAt: "2026-09-22T00:00:00Z", expiresAt: "2099-09-22T00:00:00Z",
  processedRecords: 0, errorCode: null, artifactAvailable: false, allowedActions: ["CANCEL"] };
function Accounts() {
  const { setSession } = useAuth();
  return <><button onClick={() => void setSession({ ...admin, id: "other" })}>Switch administrator</button><button onClick={() => void setSession(null)}>End session</button></>;
}
function setup(role: string | null = "ADMINISTRATOR", responder?: (url: string, init?: RequestInit) => Promise<Response>, personal = false, history = false) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return role ? Response.json({ ...admin, role }) : new Response(null, { status: 401 });
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/reauthentication")) return Response.json({ token: "a".repeat(43), expiresAt: "2099-01-01T00:00:00Z" });
    return responder ? responder(url, init) : Response.json({ items: [], nextCursor: null });
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter><AuthProvider><Accounts /><DataManagement personal={personal} history={history} /></AuthProvider></MemoryRouter></QueryClientProvider>);
  return { mock, client };
}
async function begin() {
  await screen.findByRole("heading", { name: "Data management" });
  await userEvent.click(screen.getByLabelText(/I understand this archive/));
  await userEvent.click(screen.getByRole("button", { name: "Confirm and create export" }));
}
async function confirm() {
  await userEvent.type(screen.getByLabelText("Current password"), "password");
  await userEvent.click(screen.getByRole("button", { name: "Confirm password" }));
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it.each(["MEMBER", "MODERATOR", "ADMINISTRATOR"])("keeps the %s personal workflow scoped to the account API", async role => {
  const personalJob = { ...job, kind: "PERSONAL_EXPORT" };
  const { mock, client } = setup(role, async url => Response.json(url.endsWith("/exports") ? personalJob : { items: [personalJob], nextCursor: null }), true);
  await screen.findByRole("heading", { name: "Export my data" });
  expect(screen.queryByLabelText("Include account contact details")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Include private moderation history")).not.toBeInTheDocument();
  expect(screen.getByText(/Personal archives cannot be used for company import/)).toBeVisible();
  await userEvent.click(screen.getByLabelText(/I understand this archive/));
  await userEvent.click(screen.getByRole("button", { name: "Confirm and create export" })); await confirm();
  await screen.findByText(/Export requested/);
  expect(mock.mock.calls.some(([url]) => url.includes("/admin/data/"))).toBe(false);
  expect(JSON.parse(mock.mock.calls.find(([url]) => url.endsWith("/reauthentication"))![1]!.body as string).scope).toBe("PERSONAL_EXPORT");
  const [url, init] = mock.mock.calls.find(([url]) => url.endsWith("/exports"))!;
  expect(url).toBe("/api/v1/account/data/exports");
  expect(Object.keys(JSON.parse(init!.body as string))).toEqual(["recentAuthGrant"]);
  await userEvent.click(screen.getByRole("button", { name: "End session" }));
  await screen.findByRole("heading", { name: "Sign in to manage data." });
  expect(client.getQueryCache().findAll({ queryKey: ["personalTransfers"] })).toHaveLength(0);
});

it.each(["complete", "stop", "fail"])("keeps confirmation and download feedback in the selected card: %s", async outcome => {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const save = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  vi.stubGlobal("URL", class extends URL {
    static createObjectURL = vi.fn(() => "blob:private-archive");
    static revokeObjectURL = vi.fn();
  });
  setup(undefined, async (url, init) => {
    if (url.endsWith("/download-ticket")) return Response.json({ token: "t".repeat(43) });
    if (url.endsWith("/download")) return new Response(new ReadableStream<Uint8Array>({ start(controller) {
      stream = controller;
      init!.signal!.addEventListener("abort", () => controller.error(new DOMException("Aborted", "AbortError")), { once: true });
    } }), { headers: { "Content-Type": "application/zip", "Content-Length": "4" } });
    return Response.json({ items: [{ ...job, state: "READY", artifactAvailable: true, allowedActions: ["DOWNLOAD"] }], nextCursor: null });
  });
  const reference = await screen.findByText(`Reference: ${job.id}`);
  const card = within(reference.closest("li")!);
  await userEvent.click(card.getByRole("button", { name: "Download archive" }));
  expect(card.getByLabelText("Current password")).toHaveFocus();
  expect(card.getByText(/Confirm your password below to start downloading/)).toBeVisible();
  await confirm();
  await waitFor(() => expect(stream).toBeDefined());
  await act(async () => stream.enqueue(new Uint8Array([80, 75])));
  expect(await card.findByText("Downloading: 2 bytes of 4 bytes (50%).")).toBeVisible();
  expect(card.getByRole("progressbar")).toHaveAttribute("value", "2");
  if (outcome === "stop") {
    await userEvent.click(card.getByRole("button", { name: "Stop download" }));
    expect(await card.findByRole("alert")).toHaveTextContent("Download stopped");
  } else if (outcome === "fail") {
    await act(async () => stream.error(new Error("connection lost")));
    expect(await card.findByRole("alert")).toHaveTextContent("download was interrupted");
  } else {
    await act(async () => { stream.enqueue(new Uint8Array([3, 4])); stream.close(); });
    expect(await card.findByText(/Archive sent to your browser downloads/)).toHaveTextContent(`commonbeacon-${job.id}.zip`);
  }
  expect(save).toHaveBeenCalledTimes(outcome === "complete" ? 1 : 0);
  await waitFor(() => expect(card.getByRole("button", { name: "Download archive" })).toBeEnabled());
});

it.each([null, "MEMBER", "MODERATOR"])("denies %s without fetching private jobs", async role => {
  const { mock } = setup(role);
  await screen.findByRole("heading", { name: role ? "Data management is restricted to administrators." : "Sign in to manage data." });
  expect(mock.mock.calls.every(([url]) => url.endsWith("/auth/me"))).toBe(true);
});
it("requires acknowledgement, previews defaults, and safely retries an uncertain creation", async () => {
  const requests: RequestInit[] = [];
  const { mock } = setup(undefined, async (url, init) => {
    if (url.endsWith("/exports")) { requests.push(init!); return requests.length === 1 ? new Response(null, { status: 503 }) : Response.json(job, { status: 202 }); }
    return Response.json({ items: requests.length > 1 ? [job] : [], nextCursor: null });
  });
  await screen.findByRole("heading", { name: "Data management" });
  expect(screen.getByRole("button", { name: "Confirm and create export" })).toBeDisabled();
  expect(screen.getByLabelText("Include account contact details")).not.toBeChecked();
  expect(screen.getByLabelText("Include private moderation history")).not.toBeChecked();
  await userEvent.click(screen.getByLabelText("Include account contact details"));
  expect(screen.getByText("Contact details: included.")).toBeVisible();
  await begin(); await confirm();
  await screen.findByRole("alert");
  expect(screen.getByLabelText("Include account contact details")).toBeChecked();
  await userEvent.click(screen.getByRole("button", { name: "Retry export request" })); await confirm();
  await screen.findByText(/Export requested/);
  expect(requests).toHaveLength(2);
  expect(new Headers(requests[0].headers).get("Idempotency-Key")).toBe(new Headers(requests[1].headers).get("Idempotency-Key"));
  expect(new Headers(requests[0].headers).get("X-CSRF-TOKEN")).toBe("csrf");
  expect(JSON.parse(requests[0].body as string)).toMatchObject({ includeContacts: true, includeModerationHistory: false, acknowledgedPrivateContent: true });
  expect(mock.mock.calls.filter(([url]) => url.endsWith("/reauthentication"))).toHaveLength(2);
});
it("supports fresh confirmation after an expired grant without denying the administrator", async () => {
  setup(undefined, async url => url.endsWith("/exports") ? Response.json({ code: "RECENT_AUTH_REQUIRED", detail: "Expired" }, { status: 403, headers: { "Content-Type": "application/problem+json" } }) : Response.json({ items: [], nextCursor: null }));
  await begin(); await confirm();
  expect(await screen.findByRole("alert")).toHaveTextContent("Password confirmation expired");
  expect(screen.getByRole("button", { name: "Retry export request" })).toBeEnabled();
});
it("drops late creation results and safe inputs on an administrator switch", async () => {
  let resolve!: (value: Response) => void;
  const { mock, client } = setup(undefined, async url => url.endsWith("/exports") ? new Promise(r => { resolve = r; }) : Response.json({ items: [], nextCursor: null }));
  await begin(); await confirm();
  await waitFor(() => expect(resolve).toBeDefined());
  await userEvent.click(screen.getByRole("button", { name: "Switch administrator" }));
  await waitFor(() => expect(screen.getByLabelText(/I understand this archive/)).not.toBeChecked());
  expect(mock.mock.calls.find(([url]) => url.endsWith("/exports"))![1]!.signal!.aborted).toBe(true);
  await act(async () => resolve(Response.json(job)));
  expect(screen.queryByText(/Export requested/)).not.toBeInTheDocument();
  expect(client.getQueryCache().findAll({ queryKey: ["companyTransfers", "admin"] })).toHaveLength(0);
});
it("removes the prompt, private history and query cache on logout", async () => {
  const { client } = setup(undefined, async () => Response.json({ items: [job], nextCursor: null }));
  await screen.findByText(`Reference: ${job.id}`);
  await begin(); fireEvent.change(screen.getByLabelText("Current password"), { target: { value: "private-password" } });
  await userEvent.click(screen.getByRole("button", { name: "End session" }));
  await screen.findByRole("heading", { name: "Sign in to manage data." });
  expect(screen.queryByText(`Reference: ${job.id}`)).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
  expect(client.getQueryCache().findAll({ queryKey: ["companyTransfers"] })).toHaveLength(0);
});
it("shows lifecycle states and prevents expired downloads", async () => {
  const states = [job, { ...job, state: "RUNNING", processedRecords: 19 }, { ...job, state: "FAILED", errorCode: "WORK_FAILED", allowedActions: [] },
    { ...job, state: "CANCELLED", allowedActions: [] }, { ...job, state: "READY", artifactAvailable: true, allowedActions: ["DOWNLOAD"] },
    { ...job, state: "READY", expiresAt: "2000-01-01T00:00:00Z", artifactAvailable: false, allowedActions: [] }];
  setup(undefined, async () => Response.json({ items: states.map((j, i) => ({ ...j, id: `00000000-0000-0000-0000-00000000000${i}` })), nextCursor: null }), false, true);
  await screen.findByRole("button", { name: /Archive expired/ });
  for (const label of ["Queued", "Running", "Failed", "Cancelled", "Ready to download", "Archive expired"]) {
    await userEvent.click(screen.getByRole("button", { name: new RegExp(`^${label}`) }));
    expect(screen.getByRole("heading", { name: `Company export — ${label}` })).toBeVisible();
    expect(screen.queryAllByRole("button", { name: "Download archive" })).toHaveLength(label === "Ready to download" ? 1 : 0);
    if (label === "Running") expect(screen.getByText(/19 records processed at the latest checkpoint/)).toBeVisible();
  }
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});
it("pages bounded history and sends the reviewed version when cancelling", async () => {
  const { mock } = setup(undefined, async url => url.endsWith("/cancel") ? Response.json({ ...job, state: "CANCELLED" }) : Response.json({ items: [job], nextCursor: url.includes("cursor=") ? null : "next" }), false, true);
  await userEvent.click(await screen.findByRole("button", { name: /View details/ }));
  await screen.findByText(`Reference: ${job.id}`);
  await userEvent.click(screen.getByRole("button", { name: "Older requests" }));
  await waitFor(() => expect(mock.mock.calls.some(([url]) => url.includes("size=20&kind=COMPANY_EXPORT&cursor=next"))).toBe(true));
  await userEvent.click(await screen.findByRole("button", { name: /View details/ }));
  await screen.findByText(`Reference: ${job.id}`);
  await userEvent.click(screen.getByRole("button", { name: "Cancel export" }));
  await screen.findByText(/Cancellation recorded/);
  const init = mock.mock.calls.find(([url]) => url.endsWith("/cancel"))![1]!;
  expect(JSON.parse(init.body as string)).toEqual({ expectedVersion: 0 });
  expect(new Headers(init.headers).get("Idempotency-Key")).toBeTruthy();
});
it("hides private history when a refreshed permission check denies access", async () => {
  let refused = false;
  setup(undefined, async () => refused ? Response.json({ code: "FORBIDDEN", detail: "Denied" }, { status: 403, headers: { "Content-Type": "application/problem+json" } }) : Response.json({ items: [job], nextCursor: null }));
  await screen.findByText(`Reference: ${job.id}`);
  refused = true; await userEvent.click(screen.getByRole("button", { name: "Refresh status" }));
  await screen.findByRole("heading", { name: "Data management access is no longer available." });
  expect(screen.queryByText(`Reference: ${job.id}`)).not.toBeInTheDocument();
});

it.each([false, true])("shows only the latest request, including failure, for personal=%s", async personal => {
  const latest = { ...job, kind: personal ? "PERSONAL_EXPORT" : "COMPANY_EXPORT", state: "FAILED", allowedActions: [], errorCode: "WORK_FAILED" };
  const { mock } = setup(personal ? "MEMBER" : "ADMINISTRATOR", async () => Response.json({ items: [latest, { ...latest, id: "00000000-0000-0000-0000-000000000002", state: "READY" }], nextCursor: "older" }), personal);
  await screen.findByText(`Reference: ${job.id}`);
  expect(screen.getByRole("heading", { name: /export .* Failed/ })).toBeVisible();
  expect(screen.queryByText(/Reference: 00000000-0000-0000-0000-000000000002/)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Older requests" })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "View export history" })).toHaveAttribute("href", personal ? "/account/data/history" : "/admin/data/history");
  expect(mock.mock.calls.some(([url]) => url.endsWith(`/jobs?size=1${personal ? "" : "&kind=COMPANY_EXPORT"}`))).toBe(true);
});
