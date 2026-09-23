import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "../auth/AuthProvider";
import { Imports } from "./Imports";
import { entities, readReview, readReconciliation } from "./importApi";

const id = "00000000-0000-0000-0000-000000000001";
const admin = { id: "admin", displayName: "Avery", role: "ADMINISTRATOR" };
const job = { id, kind: "COMPANY_IMPORT", state: "READY_TO_COMMIT", version: 5,
  createdAt: "2026-09-22T00:00:00Z", updatedAt: "2026-09-22T00:00:00Z", expiresAt: "2099-09-22T00:00:00Z",
  processedRecords: 4, errorCode: null, artifactAvailable: false, allowedActions: ["CANCEL"] };
const review = { jobId: id, archiveDigest: "a".repeat(64), reviewDigest: "b".repeat(64), validationVersion: 2, mappingVersion: 1,
  targetGeneration: 3, sourceInstanceId: id, formatVersion: 1, productVersion: "0.0.1", fresh: true, eligible: true,
  activationAvailable: true, expiresAt: "2099-01-01T00:00:00Z", totalErrors: 0, counts: Object.fromEntries(entities.map(e => [e, 0])),
  errors: [], warnings: ["IMPORTED_AUTHORS_INACTIVE"], exclusions: ["credentials"], mappingPreview: [], identityCollisions: 0,
  sourceOptions: { includeContacts: false, includeModerationHistory: false }, budget: { stagedBytes: 100, maxActivationBytes: 16777216 } };
function Accounts() { const { setSession } = useAuth(); return <><button onClick={() => void setSession(null)}>End session</button><button onClick={() => void setSession({ ...admin, id: "other", role: "ADMINISTRATOR" })}>Switch account</button></>; }
function setup(responder?: (url: string, init?: RequestInit) => Promise<Response>, path = `/admin/data/imports/${id}`) {
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/me")) return Response.json(admin);
    if (url.endsWith("/csrf")) return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/reauthentication")) return Response.json({ token: "g".repeat(43), expiresAt: "2099-01-01T00:00:00Z" });
    if (responder) return responder(url, init);
    return Response.json(url.endsWith("/review") ? review : url.includes("/jobs?") ? { items: [], nextCursor: null } : job);
  });
  vi.stubGlobal("fetch", mock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><AuthProvider><Accounts /><Routes><Route path="/admin/data/imports" element={<Imports />} /><Route path="/admin/data/imports/:jobId" element={<Imports />} /></Routes></AuthProvider></MemoryRouter></QueryClientProvider>);
  return { mock, client };
}
async function acknowledge() { await screen.findByRole("heading", { name: "Review before activation" }); for (const box of screen.getAllByRole("checkbox")) await userEvent.click(box); await userEvent.click(screen.getByRole("button", { name: "Confirm reviewed import" })); }
async function confirm() { await userEvent.type(screen.getByLabelText("Current password"), "private-password"); await userEvent.click(screen.getByRole("button", { name: "Confirm password" })); }
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("pins the acknowledged review and requires new acknowledgement after a stale confirmation", async () => {
  let current = review;
  const { mock } = setup(async url => {
    if (url.endsWith("/confirm")) { current = { ...review, reviewDigest: "c".repeat(64), targetGeneration: 4 }; return Response.json({ detail: "Review changed", code: "JOB_CONFLICT" }, { status: 409 }); }
    return Response.json(url.endsWith("/review") ? current : job);
  });
  await acknowledge(); await confirm();
  await screen.findByText(/No updated review will be submitted automatically/);
  await screen.findByText(`Review SHA-256: ${"c".repeat(64)}`);
  expect(screen.getByRole("button", { name: "Confirm reviewed import" })).toBeDisabled();
  const calls = mock.mock.calls.filter(([url]) => url.endsWith("/confirm")); expect(calls).toHaveLength(1);
  const body = JSON.parse(calls[0][1]!.body as string); expect(body.reviewDigest).toBe(review.reviewDigest); expect(body.targetGeneration).toBe(3); expect(body.expectedVersion).toBe(5);
  expect(body.acknowledgedWarnings).toEqual(review.warnings);
  expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
});
it("checks status after a lost confirmation response without resubmitting", async () => {
  let state = job.state;
  const { mock } = setup(async url => {
    if (url.endsWith("/confirm")) { state = "COMMITTING"; throw new TypeError("connection lost"); }
    return Response.json(url.endsWith("/review") ? review : { ...job, state, allowedActions: state === "COMMITTING" ? [] : ["CANCEL"] });
  });
  await acknowledge(); await confirm(); await screen.findByRole("button", { name: "Check outcome" });
  await userEvent.click(screen.getByRole("button", { name: "Check outcome" }));
  await screen.findByText("Activating atomically; cancellation is closed");
  expect(screen.queryByRole("button", { name: "Cancel import" })).not.toBeInTheDocument();
  expect(mock.mock.calls.filter(([url]) => url.endsWith("/confirm"))).toHaveLength(1);
});
it("uses the new job version after reading a stale review before requesting another dry run", async () => {
  let version = 5;
  const { mock } = setup(async url => {
    if (url.endsWith("/review")) { version = 6; return Response.json({ ...review, fresh: false, eligible: false, activationAvailable: false }); }
    return Response.json({ ...job, version, state: version === 6 ? "REVIEW_REQUIRED" : job.state });
  });
  await screen.findByText(/This review is stale/);
  await userEvent.click(screen.getByRole("button", { name: "Run a fresh dry run" }));
  await waitFor(() => expect(mock.mock.calls.some(([url]) => url.endsWith("/dry-run"))).toBe(true));
  expect(JSON.parse(mock.mock.calls.find(([url]) => url.endsWith("/dry-run"))![1]!.body as string)).toEqual({ expectedVersion: 6 });
  expect(mock.mock.calls.some(([url]) => url.endsWith("/confirm"))).toBe(false);
});
it("clears private files, password and cached reviews on account changes", async () => {
  const { client } = setup(undefined, "/admin/data/imports");
  const input = await screen.findByLabelText("Archive file");
  await userEvent.upload(input, new File(["zip"], "private-customer-data.zip", { type: "application/zip" }));
  await userEvent.click(screen.getByRole("button", { name: "Confirm password and upload" }));
  await userEvent.type(screen.getByLabelText("Current password"), "private-password");
  await userEvent.click(screen.getByRole("button", { name: "Switch account" }));
  await waitFor(() => expect(screen.queryByText(/private-customer-data/)).not.toBeInTheDocument());
  expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
  expect(client.getQueryCache().findAll({ queryKey: ["imports", "admin"] })).toHaveLength(0);
});
it("discards a late private preview after logout", async () => {
  let resolve!: (value: Response) => void;
  setup(async url => url.endsWith("/review") ? new Promise<Response>(r => { resolve = r; }) : Response.json(job));
  await waitFor(() => expect(resolve).toBeDefined()); await userEvent.click(screen.getByRole("button", { name: "End session" }));
  await act(async () => resolve(Response.json(review)));
  expect(screen.queryByText(/Archive SHA-256/)).not.toBeInTheDocument(); expect(screen.getByText("Sign in to import data.")).toBeVisible();
});
it("rejects invalid files before requesting a grant", async () => {
  const { mock } = setup(undefined, "/admin/data/imports");
  const user = userEvent.setup({ applyAccept: false });
  await user.upload(await screen.findByLabelText("Archive file"), new File(["x"], "personal.txt"));
  await screen.findByText("Choose a nonempty .zip file no larger than 64 MiB.");
  expect(screen.getByRole("button", { name: "Confirm password and upload" })).toBeDisabled();
  expect(mock.mock.calls.some(([url]) => url.endsWith("/reauthentication"))).toBe(false);
});
it("decodes bounded reports without trusting malformed counts or IDs", () => {
  expect(readReview(review).exclusions).toEqual(["credentials"]);
  expect(() => readReview({ ...review, errors: [{ file: "users.jsonl", line: -1, code: "INVALID" }] })).toThrow();
  expect(() => readReview({ ...review, mappingPreview: [{ entity: "users", sourceId: "unsafe", localId: id }] })).toThrow();
  expect(() => readReconciliation({ jobId: id, state: "COMMITTING" })).toThrow();
});
