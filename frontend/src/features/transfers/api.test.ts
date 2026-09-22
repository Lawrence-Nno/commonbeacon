import { afterEach, expect, it, vi } from "vitest";
import { downloadArchive, readJob, readPage } from "./api";
import type { Job } from "./api";
const job: Job = { id: "00000000-0000-0000-0000-000000000001", kind: "COMPANY_EXPORT", state: "READY", version: 1,
  createdAt: "2026-09-22T00:00:00Z", updatedAt: "2026-09-22T00:00:00Z", expiresAt: "2099-09-22T00:00:00Z",
  processedRecords: 3, errorCode: null, artifactAvailable: true, allowedActions: ["DOWNLOAD"] };
const ticket = "t".repeat(43);
function setup(response: () => Response) {
  const mock = vi.fn(async (url: string) => url.endsWith("/csrf") ? Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf" })
    : url.endsWith("/download-ticket") ? Response.json({ token: ticket }) : response());
  vi.stubGlobal("fetch", mock); return mock;
}
afterEach(() => vi.unstubAllGlobals());
it("rejects unsafe identifiers, malformed states and oversized histories", () => {
  for (const value of [{ ...job, id: "../private" }, { ...job, state: "UNKNOWN" }, { ...job, version: -1 }, { ...job, createdAt: "bad" }, { ...job, allowedActions: ["DELETE"] }]) expect(() => readJob(value)).toThrow();
  expect(() => readPage({ items: Array(21).fill(job), nextCursor: null })).toThrow();
  expect(() => readPage({ items: [], nextCursor: "../../bad" })).toThrow();
  expect(() => readPage({ items: [job], nextCursor: null }, true)).toThrow();
  expect(() => readPage({ items: [{ ...job, kind: "PERSONAL_EXPORT" }], nextCursor: null })).toThrow();
});
it("downloads only complete bounded ZIP bytes with a header ticket and no-store", async () => {
  const mock = setup(() => new Response(new Uint8Array([80, 75, 3, 4]), { headers: { "Content-Type": "application/zip", "Content-Length": "4" } }));
  const controller = new AbortController();
  const progress = vi.fn();
  const blob = await downloadArchive(job, "grant", controller.signal, progress);
  expect(blob.size).toBe(4);
  expect(progress.mock.calls).toEqual([[0, 4], [4, 4]]);
  const calls = mock.mock.calls as unknown as [string, RequestInit][];
  const [url, init] = calls.at(-1)!;
  expect(url).not.toContain(ticket); expect(url).not.toContain("grant");
  expect(init.cache).toBe("no-store"); expect(new Headers(init.headers).get("X-Download-Ticket")).toBe(ticket);
});
it.each([
  { type: "application/json", length: "4", bytes: 4 },
  { type: "application/zip", length: "67108865", bytes: 4 },
  { type: "application/zip", length: "8", bytes: 4 },
  { type: "application/zip", length: "2", bytes: 4 },
])("rejects nonarchives, excessive sizes and partial streams: %j", async ({ type, length, bytes }) => {
  setup(() => new Response(new Uint8Array(bytes), { headers: { "Content-Type": type, "Content-Length": length } }));
  await expect(downloadArchive(job, "grant", new AbortController().signal)).rejects.toMatchObject({ kind: "invalid-response" });
});
it("explains expired downloads and reports session expiry", async () => {
  setup(() => new Response(null, { status: 410 }));
  await expect(downloadArchive(job, "grant", new AbortController().signal)).rejects.toMatchObject({ status: 410 });
  const expired = vi.fn(); window.addEventListener("commonbeacon:session-expired", expired);
  try {
    setup(() => new Response(null, { status: 401 }));
    await expect(downloadArchive(job, "grant", new AbortController().signal)).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
  } finally { window.removeEventListener("commonbeacon:session-expired", expired); }
});
