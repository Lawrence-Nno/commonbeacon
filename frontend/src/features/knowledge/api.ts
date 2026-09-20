import { ApiError, getJson, postJson } from "../../lib/http";
export type Status = "DRAFT" | "PUBLISHED" | "ARCHIVED";
export type Summary = { id: string; slug: string; title: string; author: { id: string; displayName: string }; publishedAt: string; updatedAt: string };
export type Article = Summary & { body: string };
export type AdminSummary = Omit<Summary, "publishedAt"> & { publishedAt: string | null; status: Status; createdAt: string; version: number };
export type AdminArticle = AdminSummary & { body: string };
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };
export type Input = { slug: string; title: string; body: string };
function invalid(): never { throw new ApiError("invalid-response", "The article service returned an invalid response."); }
function obj(v: unknown): Record<string, unknown> { return v !== null && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : invalid(); }
function text(v: unknown): string { return typeof v === "string" ? v : invalid(); }
function count(v: unknown): number { return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : invalid(); }
function common(v: Record<string, unknown>) {
  const a = obj(v.author);
  return { id: text(v.id), slug: text(v.slug), title: text(v.title), author: { id: text(a.id), displayName: text(a.displayName) }, updatedAt: text(v.updatedAt) };
}
function summary(v: unknown): Summary { const a = obj(v); return { ...common(a), publishedAt: text(a.publishedAt) }; }
function adminSummary(v: unknown): AdminSummary {
  const a = obj(v);
  if (a.status !== "DRAFT" && a.status !== "PUBLISHED" && a.status !== "ARCHIVED") return invalid();
  return { ...common(a), status: a.status, publishedAt: a.publishedAt === null ? null : text(a.publishedAt), createdAt: text(a.createdAt), version: count(a.version) };
}
export function readArticle(v: unknown): Article { return { ...summary(v), body: text(obj(v).body) }; }
export function readAdminArticle(v: unknown): AdminArticle { return { ...adminSummary(v), body: text(obj(v).body) }; }
function page<T>(v: unknown, decode: (v: unknown) => T): Page<T> {
  const p = obj(v), size = count(p.size);
  if (!Array.isArray(p.items) || size < 1 || size > 100) return invalid();
  return { items: p.items.map(decode), page: count(p.page), size, totalElements: count(p.totalElements), totalPages: count(p.totalPages) };
}
export const listArticles = (index: number, signal?: AbortSignal) => getJson(`/api/v1/articles?page=${index}&size=20`, (v) => page(v, summary), signal);
export const getArticle = (slug: string, signal?: AbortSignal) => getJson(`/api/v1/articles/${encodeURIComponent(slug)}`, readArticle, signal);
export const listAdminArticles = (status: string, index: number, signal?: AbortSignal) => getJson(`/api/v1/admin/articles?page=${index}&size=20${status ? `&status=${status}` : ""}`, (v) => page(v, adminSummary), signal);
export const getAdminArticle = (id: string, signal?: AbortSignal) => getJson(`/api/v1/admin/articles/${encodeURIComponent(id)}`, readAdminArticle, signal);
export const createArticle = (input: Input) => postJson("/api/v1/admin/articles", input, readAdminArticle);
export const saveArticle = (base: AdminArticle, input: Input) => postJson(`/api/v1/admin/articles/${encodeURIComponent(base.id)}`, { title: input.title, body: input.body, expectedVersion: base.version }, readAdminArticle, "PATCH");
export const transitionArticle = (base: AdminArticle, action: "publish" | "archive") => postJson(`/api/v1/admin/articles/${encodeURIComponent(base.id)}/${action}`, { expectedVersion: base.version }, readAdminArticle);
