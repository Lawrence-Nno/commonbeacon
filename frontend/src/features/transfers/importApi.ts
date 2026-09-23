import { ApiError, getJson, postJson } from "../../lib/http";
import { readJob, readPage } from "./api";
import type { Job } from "./api";

export const entities = ["users", "boards", "questions", "replies", "acceptances", "articles", "contacts", "reports", "actions"] as const;
export type Entity = typeof entities[number];
export type Mapping = { entity: Entity; sourceId: string; localId: string };
export type Issue = { file: string; line: number; code: string };
export type Review = {
  jobId: string; archiveDigest: string; reviewDigest: string; validationVersion: number; mappingVersion: number;
  targetGeneration: number; sourceInstanceId?: string; formatVersion?: number; productVersion?: string;
  fresh: boolean; eligible: boolean; activationAvailable: boolean; expiresAt: string; totalErrors: number;
  counts: Record<Entity, number>; errors: Issue[]; warnings: string[]; exclusions: string[]; sourceWarnings: string[];
  mappingPreview: Mapping[]; identityCollisions: number;
  sourceOptions?: { includeContacts: boolean; includeModerationHistory: boolean };
  budget: { stagedBytes: number; maxActivationBytes: number };
};
export type Reconciliation = { jobId: string; state: string; detailsAvailable: boolean; review: Review | null;
  counts: Record<Entity, { expected: number | null; created: number; skipped: number | null; rejected: number | null }>;
  mappings: Mapping[]; nextCursor: string | null };
const root = "/api/v1/admin/data/imports";
function bad(): never { throw new ApiError("invalid-response", "The import service returned an unexpected response. Refresh status before continuing."); }
function obj(v: unknown): Record<string, unknown> { return v !== null && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : bad(); }
function str(v: unknown, max = 512): string { return typeof v === "string" && v.length <= max ? v : bad(); }
function bool(v: unknown): boolean { return typeof v === "boolean" ? v : bad(); }
function number(v: unknown): number { return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : bad(); }
function uuid(v: unknown): string { const s = str(v, 36); return /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(s) ? s : bad(); }
function digest(v: unknown): string { const s = str(v, 64); return /^[a-f0-9]{64}$/.test(s) ? s : bad(); }
function strings(v: unknown, limit = 64): string[] { return Array.isArray(v) && v.length <= limit ? v.map(x => str(x)) : bad(); }
function mappings(v: unknown): Mapping[] { return Array.isArray(v) && v.length <= 100 ? v.map(item => {
  const m = obj(item); if (!entities.includes(m.entity as Entity)) return bad();
  return { entity: m.entity as Entity, sourceId: uuid(m.sourceId), localId: uuid(m.localId) };
}) : bad(); }
function issues(v: unknown): Issue[] { return Array.isArray(v) && v.length <= 1000 ? v.map(item => {
  const i = obj(item); return { file: str(i.file, 100), line: number(i.line), code: str(i.code, 100) };
}) : bad(); }
function cursor(v: unknown): string | null { return v === null ? null : typeof v === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(v) ? v : bad(); }
export function readReview(value: unknown): Review {
  const r = obj(value), counts = obj(r.counts), budget = obj(r.budget);
  const expiresAt = str(r.expiresAt); if (!Number.isFinite(Date.parse(expiresAt))) return bad();
  const options = r.sourceOptions === undefined ? undefined : obj(r.sourceOptions);
  return { jobId: uuid(r.jobId), archiveDigest: digest(r.archiveDigest), reviewDigest: digest(r.reviewDigest),
    validationVersion: number(r.validationVersion), mappingVersion: number(r.mappingVersion), targetGeneration: number(r.targetGeneration),
    sourceInstanceId: r.sourceInstanceId === undefined ? undefined : uuid(r.sourceInstanceId),
    formatVersion: r.formatVersion === undefined ? undefined : number(r.formatVersion), productVersion: r.productVersion === undefined ? undefined : str(r.productVersion),
    fresh: bool(r.fresh), eligible: bool(r.eligible), activationAvailable: bool(r.activationAvailable), expiresAt, totalErrors: number(r.totalErrors),
    counts: Object.fromEntries(entities.map(e => [e, number(counts[e])])) as Review["counts"], errors: issues(r.errors),
    warnings: strings(r.warnings), exclusions: r.exclusions === undefined ? [] : strings(r.exclusions), mappingPreview: mappings(r.mappingPreview),
    sourceWarnings: r.sourceWarnings === undefined ? [] : strings(r.sourceWarnings),
    identityCollisions: number(r.identityCollisions), sourceOptions: options && { includeContacts: bool(options.includeContacts), includeModerationHistory: bool(options.includeModerationHistory) },
    budget: { stagedBytes: number(budget.stagedBytes), maxActivationBytes: budget.maxActivationBytes === undefined ? 16777216 : number(budget.maxActivationBytes) } };
}
export function readReconciliation(value: unknown): Reconciliation {
  const r = obj(value), counts = obj(r.counts); if (!["COMPLETED", "FAILED", "CANCELLED"].includes(String(r.state))) return bad();
  const nullable = (n: unknown) => n === null ? null : number(n);
  return { jobId: uuid(r.jobId), state: str(r.state), detailsAvailable: bool(r.detailsAvailable), review: r.review === null ? null : readReview(r.review),
    counts: Object.fromEntries(entities.map(e => { const c = obj(counts[e]); return [e, { expected: nullable(c.expected), created: number(c.created), skipped: nullable(c.skipped), rejected: nullable(c.rejected) }]; })) as Reconciliation["counts"],
    mappings: mappings(r.mappings), nextCursor: cursor(r.nextCursor) };
}
export const importHistory = (after: string | null, signal?: AbortSignal) => getJson(`/api/v1/admin/data/jobs?size=20&kind=COMPANY_IMPORT${after ? `&cursor=${encodeURIComponent(after)}` : ""}`, v => {
  const page = readPage(v); if (page.items.some(j => j.kind !== "COMPANY_IMPORT")) return bad(); return page;
}, signal);
export const importStatus = (id: string, signal?: AbortSignal) => getJson(`/api/v1/admin/data/jobs/${uuid(id)}`, v => { const j = readJob(v); return j.kind === "COMPANY_IMPORT" && j.id === id ? j : bad(); }, signal);
export const createImport = (grant: string, key: string, signal: AbortSignal) => postJson(root, { formatVersion: 1, recentAuthGrant: grant }, readJob, "POST", { signal, idempotencyKey: key });
export const dryRun = (job: Job, key: string, signal: AbortSignal) => postJson(`${root}/${uuid(job.id)}/dry-run`, { expectedVersion: job.version }, readJob, "POST", { signal, idempotencyKey: key });
export const reviewImport = (id: string, signal: AbortSignal) => getJson(`${root}/${uuid(id)}/review`, v => { const r = readReview(v); return r.jobId === id ? r : bad(); }, signal, 15_000);
export const reconcileImport = (id: string, after: string | null, signal: AbortSignal) => getJson(`${root}/${uuid(id)}/reconciliation${after ? `?cursor=${encodeURIComponent(after)}` : ""}`, v => { const r = readReconciliation(v); return r.jobId === id ? r : bad(); }, signal);
export const inspectImport = (id: string, signal: AbortSignal) => getJson(`${root}/${uuid(id)}/inspection`, v => {
  const r = obj(v); if (uuid(r.jobId) !== id) return bad();
  return { valid: bool(r.valid), issues: issues(r.issues), totalErrors: number(r.totalErrors) };
}, signal);
export const activateImport = (job: Job, review: Review, grant: string, key: string, signal: AbortSignal) => postJson(`${root}/${uuid(job.id)}/confirm`, {
  expectedVersion: job.version, reviewDigest: review.reviewDigest, archiveDigest: review.archiveDigest, targetGeneration: review.targetGeneration,
  acknowledgedPrivateContent: true, acknowledgedInactiveAuthors: true, acknowledgedWarnings: review.warnings, recentAuthGrant: grant,
}, readJob, "POST", { signal, idempotencyKey: key });

/** Browser-owned File, streamed by XHR for real upload progress; never persisted. */
export async function uploadImport(id: string, file: File, signal: AbortSignal, progress: (bytes: number) => void): Promise<Job> {
  if (file.size < 1 || file.size > 67108864) throw new ApiError("http", "Choose a nonempty ZIP no larger than 64 MiB.");
  const csrf = await getJson("/api/v1/auth/csrf", v => { const c = obj(v); return c.headerName === "X-CSRF-TOKEN" ? { header: c.headerName, token: str(c.token, 1024) } : bad(); }, signal);
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => { xhr.abort(); reject(new DOMException("Upload stopped", "AbortError")); };
    const cleanup = () => signal.removeEventListener("abort", abort);
    xhr.open("PUT", `${root}/${uuid(id)}/archive`); xhr.withCredentials = true; xhr.timeout = 120_000;
    xhr.setRequestHeader("Content-Type", "application/zip"); xhr.setRequestHeader(csrf.header, csrf.token); xhr.setRequestHeader("Accept", "application/json");
    xhr.upload.onprogress = e => { if (!signal.aborted) progress(Math.min(e.loaded, file.size)); };
    xhr.onload = () => { cleanup(); if (signal.aborted) return reject(signal.reason);
      if (xhr.status === 401) window.dispatchEvent(new Event("commonbeacon:session-expired"));
      try {
        if (xhr.status !== 200) throw new ApiError("http", "Upload was not confirmed. Check status before retrying the whole file.", xhr.status);
        if (!xhr.getResponseHeader("Content-Type")?.includes("application/json")) return bad();
        const job = readJob(JSON.parse(xhr.responseText)); if (job.id !== id || job.kind !== "COMPANY_IMPORT") return bad(); resolve(job);
      } catch (error) { reject(error); }
    };
    xhr.onerror = () => { cleanup(); reject(new ApiError("network", "Upload interrupted. Check status before retrying the whole file.")); };
    xhr.ontimeout = () => { cleanup(); reject(new ApiError("timeout", "Upload timed out. Check status before retrying the whole file.")); };
    xhr.onabort = cleanup; signal.addEventListener("abort", abort, { once: true }); progress(0); xhr.send(file);
  });
}
