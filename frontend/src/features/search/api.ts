import { ApiError, getJson } from "../../lib/http";

export type SearchHit = { kind: "ARTICLE" | "QUESTION"; id: string; title: string; snippet: string; url: string; rank: number };
export type SearchPage = { items: SearchHit[]; page: number; size: number; totalElements: number; totalPages: number };
function invalid(): never { throw new ApiError("invalid-response", "The search service returned an invalid response."); }
function object(v: unknown): Record<string, unknown> { return v !== null && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : invalid(); }
function integer(v: unknown): number { return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : invalid(); }
export function readSearchPage(value: unknown): SearchPage {
  const page = object(value), size = integer(page.size);
  if (!Array.isArray(page.items) || size < 1 || size > 100) return invalid();
  const items = page.items.map((value): SearchHit => {
    const hit = object(value);
    if ((hit.kind !== "ARTICLE" && hit.kind !== "QUESTION") || typeof hit.id !== "string"
      || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(hit.id)
      || typeof hit.title !== "string" || typeof hit.snippet !== "string" || hit.snippet.length > 240
      || typeof hit.url !== "string" || (hit.kind === "QUESTION" ? hit.url !== `/questions/${hit.id}` : !/^\/knowledge\/[a-z0-9]+(?:-[a-z0-9]+)*$/.test(hit.url))
      || typeof hit.rank !== "number" || !Number.isFinite(hit.rank) || hit.rank < 0) return invalid();
    return { kind: hit.kind, id: hit.id, title: hit.title, snippet: hit.snippet, url: hit.url, rank: hit.rank };
  });
  return { items, size, page: integer(page.page), totalElements: integer(page.totalElements), totalPages: integer(page.totalPages) };
}
export const search = (q: string, page: number, signal?: AbortSignal) => getJson(`/api/v1/search?${new URLSearchParams({ q, page: String(page), size: "20" })}`, readSearchPage, signal);
