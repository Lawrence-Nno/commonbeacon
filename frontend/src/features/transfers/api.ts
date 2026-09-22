import { ApiError, getJson, postJson } from "../../lib/http";

export type ExportOptions = { includeContacts: boolean; includeModerationHistory: boolean };
export type Job = {
  id: string; kind: string; state: string; version: number; createdAt: string;
  updatedAt: string; expiresAt: string; processedRecords: number; errorCode: string | null;
  artifactAvailable: boolean; allowedActions: string[];
};
export type JobPage = { items: Job[]; nextCursor: string | null };
const root = "/api/v1/admin/data";
const personalRoot = "/api/v1/account/data";
const jobRoot = (job: Job) => job.kind === "PERSONAL_EXPORT" ? personalRoot : root;
function invalid(): never { throw new ApiError("invalid-response", "The transfer service returned an unexpected response."); }
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
function timestamp(value: unknown): string { return typeof value === "string" && Number.isFinite(Date.parse(value)) ? value : invalid(); }
function count(value: unknown): number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : invalid(); }
export function readJob(value: unknown): Job {
  const v = object(value);
  if (typeof v.id !== "string" || !/^[0-9a-f-]{36}$/i.test(v.id)
    || !["COMPANY_EXPORT", "COMPANY_IMPORT", "PERSONAL_EXPORT"].includes(String(v.kind))
    || !["QUEUED", "RUNNING", "READY", "FAILED", "CANCELLED", "UPLOADING", "UPLOADED", "VALIDATING", "REVIEW_REQUIRED", "READY_TO_COMMIT", "COMMITTING", "COMPLETED"].includes(String(v.state))
    || typeof v.artifactAvailable !== "boolean" || !(v.errorCode === null || typeof v.errorCode === "string")
    || !Array.isArray(v.allowedActions) || !v.allowedActions.every(a => a === "CANCEL" || a === "DOWNLOAD")) return invalid();
  return { id: v.id, kind: v.kind as string, state: v.state as string, version: count(v.version),
    createdAt: timestamp(v.createdAt), updatedAt: timestamp(v.updatedAt), expiresAt: timestamp(v.expiresAt),
    processedRecords: count(v.processedRecords), errorCode: v.errorCode, artifactAvailable: v.artifactAvailable,
    allowedActions: v.allowedActions as string[] };
}
export function readPage(value: unknown, personal = false): JobPage {
  const v = object(value);
  if (!Array.isArray(v.items) || v.items.length > 20 || !(v.nextCursor === null || typeof v.nextCursor === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(v.nextCursor))) return invalid();
  const items = v.items.map(readJob);
  if (items.some(job => (job.kind === "PERSONAL_EXPORT") !== personal)) return invalid();
  return { items, nextCursor: v.nextCursor };
}
export const listJobs = (cursor: string | null, signal?: AbortSignal, personal = false) => getJson(`${personal ? personalRoot : root}/jobs?size=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`, value => readPage(value, personal), signal);
export const createExport = (options: ExportOptions, grant: string, key: string, signal?: AbortSignal) =>
  postJson(`${root}/exports`, { ...options, acknowledgedPrivateContent: true, recentAuthGrant: grant }, readJob, "POST", { signal, idempotencyKey: key });
export const createPersonalExport = (grant: string, key: string, signal?: AbortSignal) =>
  postJson(`${personalRoot}/exports`, { recentAuthGrant: grant }, readJob, "POST", { signal, idempotencyKey: key });
export const cancelJob = (job: Job, key: string, signal?: AbortSignal) => postJson(`${jobRoot(job)}/jobs/${job.id}/cancel`,
  { expectedVersion: job.version }, readJob, "POST", { signal, idempotencyKey: key });

/** Tickets stay in memory and travel in a header, never a URL or browser storage. */
export async function downloadArchive(job: Job, grant: string, signal: AbortSignal,
  onProgress?: (received: number, total: number) => void): Promise<Blob> {
  const ticket = await postJson(`${jobRoot(job)}/jobs/${job.id}/download-ticket`, { recentAuthGrant: grant }, value => {
    const v = object(value);
    return typeof v.token === "string" && /^[A-Za-z0-9_-]{43}$/.test(v.token) ? v.token : invalid();
  }, "POST", { signal });
  const timeout = AbortSignal.timeout(120_000);
  const combined = AbortSignal.any([signal, timeout]);
  try {
    const response = await fetch(`${jobRoot(job)}/jobs/${job.id}/download`, { credentials: "same-origin", cache: "no-store", signal: combined,
      headers: { Accept: "application/zip", "X-Download-Ticket": ticket } });
    if (!response.ok) {
      if (response.status === 401) window.dispatchEvent(new Event("commonbeacon:session-expired"));
      const problem: unknown = response.headers.get("content-type")?.includes("application/problem+json") ? await response.json().catch(() => null) : null;
      const code = problem !== null && typeof problem === "object" && "code" in problem && problem.code === "RECENT_AUTH_REQUIRED" ? "RECENT_AUTH_REQUIRED" : undefined;
      throw new ApiError("http", response.status === 410 ? "This archive has expired. Create a new export." : "Download unavailable. Refresh the job and confirm your password to try again.", response.status, undefined, undefined, code);
    }
    if (!response.headers.get("content-type")?.startsWith("application/zip") || !response.body) return invalid();
    const limit = 64 * 1024 * 1024;
    const declared = Number(response.headers.get("content-length"));
    if (!Number.isSafeInteger(declared) || declared < 1 || declared > limit) { await response.body.cancel(); return invalid(); }
    const reader = response.body.getReader();
    const chunks: Uint8Array<ArrayBuffer>[] = [];
    let bytes = 0;
    onProgress?.(0, declared);
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        bytes += part.value.byteLength;
        if (bytes > limit || bytes > declared) return invalid();
        chunks.push(new Uint8Array(part.value));
        onProgress?.(bytes, declared);
      }
      if (bytes !== declared) return invalid();
      combined.throwIfAborted();
      return new Blob(chunks, { type: "application/zip" });
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  } catch (error) {
    if (signal.aborted || error instanceof ApiError) throw error;
    throw new ApiError(timeout.aborted ? "timeout" : "network", "The download was interrupted. Refresh the job and confirm your password to try again.");
  }
}
