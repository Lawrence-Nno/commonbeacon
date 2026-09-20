import { useQuery } from "@tanstack/react-query";
import { Link, useParams, useSearchParams } from "react-router";
import { ApiError } from "../../lib/http";
import { getArticle, listArticles } from "./api";

export function ArticleFailure({ error, retry, busy }: { error: Error; retry: () => void; busy: boolean }) {
  const status = error instanceof ApiError ? error.status : undefined;
  const terminal = [400, 401, 403, 404].includes(status ?? 0);
  return <div role="alert" className="form-error"><p>{status === 404 ? "Article not found. It is not available to read." : status === 401 || status === 403 ? "Your account cannot access these articles." : error.message}</p>
    {!terminal && <button className="button button-secondary" disabled={busy} onClick={retry}>Retry articles</button>}</div>;
}
export function ArticlePages({ page, totalPages, go }: { page: number; totalPages: number; go: (page: number) => void }) {
  return totalPages > 1 || page > 0 ? <nav className="pagination" aria-label="Article pages">
    <button className="button button-secondary" disabled={page === 0} onClick={() => go(page - 1)}>Previous articles</button>
    <span>Page {page + 1} of {Math.max(1, totalPages)}</span>
    <button className="button button-secondary" disabled={page + 1 >= totalPages} onClick={() => go(page + 1)}>Next articles</button>
    {page >= totalPages && <button className="text-link" onClick={() => go(0)}>First page</button>}
  </nav> : null;
}
export function KnowledgePage() {
  const [params, setParams] = useSearchParams();
  const raw = params.get("page") ?? "0", page = /^\d+$/.test(raw) ? Number(raw) : -1;
  const valid = Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const query = useQuery({ queryKey: ["articles", "list", page], queryFn: ({ signal }) => listArticles(page, signal), enabled: valid, retry: false });
  return <section className="board-page"><p className="eyebrow">SHARED KNOWLEDGE</p><h1>Knowledge library</h1><p>Practical guides from the community team.</p>
    {!valid ? <p role="alert">Invalid article page. <button className="text-link" onClick={() => setParams({ page: "0" })}>Reset article page</button></p>
      : query.isPending ? <p role="status">Loading articles...</p> : query.isError ? <ArticleFailure error={query.error} busy={query.isFetching} retry={() => void query.refetch()} /> : <>
        {query.data.items.length === 0 ? <p className="empty-state">{page === 0 ? "No published articles yet." : "No articles on this page."}</p> : <ol className="moderation-list">{query.data.items.map((a) =>
          <li className="reply-card" key={a.id}><h2><Link className="text-link" to={`/knowledge/${encodeURIComponent(a.slug)}`}>{a.title}</Link></h2><p>By {a.author.displayName}</p><p>Published <time dateTime={a.publishedAt}>{new Date(a.publishedAt).toLocaleString()}</time></p></li>)}</ol>}
        <ArticlePages page={page} totalPages={query.data.totalPages} go={(page) => setParams({ page: String(page) })} />
      </>}
  </section>;
}
export function KnowledgeArticlePage() {
  const { slug = "" } = useParams();
  const query = useQuery({ queryKey: ["articles", "detail", slug], queryFn: ({ signal }) => getArticle(slug, signal), retry: false });
  return <article className="board-page"><Link className="text-link" to="/knowledge">Back to knowledge</Link>
    {query.isPending ? <p role="status">Loading article...</p> : query.isError ? <ArticleFailure error={query.error} busy={query.isFetching} retry={() => void query.refetch()} /> : <>
      <h1>{query.data.title}</h1><p>By {query.data.author.displayName}</p>
      <p>Published <time dateTime={query.data.publishedAt}>{new Date(query.data.publishedAt).toLocaleString()}</time></p>
      <p>Updated <time dateTime={query.data.updatedAt}>{new Date(query.data.updatedAt).toLocaleString()}</time></p>
      <div className="question-body">{query.data.body}</div>
    </>}
  </article>;
}
