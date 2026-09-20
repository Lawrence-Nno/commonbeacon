import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../../lib/http";
import { ArticleFailure, ArticlePages } from "./KnowledgePage";
import { createArticle, getAdminArticle, listAdminArticles, saveArticle, transitionArticle } from "./api";
import type { AdminArticle, Input } from "./api";

export function AdminArticles({ creating = false }: { creating?: boolean }) {
  const { user, sessionError } = useAuth();
  const { articleId } = useParams();
  if (user === undefined) return <p role="status">{sessionError ? "Account connection unavailable. Reload to try again." : "Checking your account..."}</p>;
  if (!user) return <section className="board-page"><h1>Sign in to manage articles.</h1><Link to="/login">Sign in</Link></section>;
  if (user.role !== "ADMINISTRATOR") return <section className="board-page"><h1>Article administration is restricted.</h1><Link to="/knowledge">Browse published articles</Link></section>;
  return creating ? <Editor key={user.id + ":new"} actorId={user.id} /> : articleId ? <LoadEditor key={user.id + articleId} id={articleId} actorId={user.id} /> : <AdminList key={user.id} actorId={user.id} />;
}
function AdminList({ actorId }: { actorId: string }) {
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "", raw = params.get("page") ?? "0", page = /^\d+$/.test(raw) ? Number(raw) : -1;
  const valid = ["", "DRAFT", "PUBLISHED", "ARCHIVED"].includes(status) && Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const query = useQuery({ queryKey: ["adminArticles", actorId, "list", status, page], queryFn: ({ signal }) => listAdminArticles(status, page, signal), enabled: valid, retry: false });
  return <section className="board-page"><h1>Manage articles</h1><Link className="button button-primary" to="/admin/articles/new">Create article</Link>
    <div className="question-filter"><label htmlFor="article-status">Article status</label><select id="article-status" value={status} onChange={(e) => setParams({ status: e.target.value, page: "0" })}>
      <option value="">All statuses</option><option value="DRAFT">Draft</option><option value="PUBLISHED">Published</option><option value="ARCHIVED">Archived</option></select></div>
    {!valid ? <p role="alert">Invalid article filters. <button className="text-link" onClick={() => setParams({})}>Reset article filters</button></p>
      : query.isPending ? <p role="status">Loading articles...</p> : query.isError ? <ArticleFailure error={query.error} busy={query.isFetching} retry={() => void query.refetch()} /> : <>
        <p>{query.data.totalElements} articles</p>
        {query.data.items.length === 0 ? <p className="empty-state">No articles with these filters on this page.</p> : <ol className="moderation-list">{query.data.items.map((a) =>
          <li className="reply-card" key={a.id}><h2><Link className="text-link" to={`/admin/articles/${a.id}`}>{a.title}</Link></h2><p>{a.status} · By {a.author.displayName}</p><p>Updated <time dateTime={a.updatedAt}>{new Date(a.updatedAt).toLocaleString()}</time></p></li>)}</ol>}
        <ArticlePages page={page} totalPages={query.data.totalPages} go={(page) => setParams({ status, page: String(page) })} />
      </>}
  </section>;
}
function LoadEditor({ id, actorId }: { id: string; actorId: string }) {
  const query = useQuery({ queryKey: ["adminArticles", actorId, "detail", id], queryFn: ({ signal }) => getAdminArticle(id, signal), retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false });
  if (query.isPending) return <p role="status">Loading article editor...</p>;
  if (query.isError) return <section className="board-page"><h1>Article editor unavailable</h1><ArticleFailure error={query.error} busy={query.isFetching} retry={() => void query.refetch()} /></section>;
  return <Editor initial={query.data} actorId={actorId} />;
}
function Editor({ initial, actorId }: { initial?: AdminArticle; actorId: string }) {
  const [base, setBase] = useState(initial);
  const [input, setInput] = useState<Input>({ slug: initial?.slug ?? "", title: initial?.title ?? "", body: initial?.body ?? "" });
  const [latest, setLatest] = useState<AdminArticle>();
  const [busy, setBusy] = useState(false), [blocked, setBlocked] = useState(false), [denied, setDenied] = useState(false);
  const [error, setError] = useState(""), [message, setMessage] = useState("");
  const alive = useRef(true), controller = useRef<AbortController | null>(null);
  const client = useQueryClient(), navigate = useNavigate();
  useEffect(() => { alive.current = true; return () => { alive.current = false; controller.current?.abort(); }; }, []);
  const dirty = !!base && (input.title !== base.title || input.body !== base.body);
  function failure(value: unknown) {
    setError(value instanceof Error ? value.message : "Could not save the article.");
    if (value instanceof ApiError && value.fieldErrors) setError(Object.entries(value.fieldErrors).map(([key, value]) => `${key}: ${value}`).join("; "));
    if (value instanceof ApiError && [401, 403].includes(value.status ?? 0)) setDenied(true);
    // Existing records must be reviewed after uncertain or conflicting responses.
    if (base && (!(value instanceof ApiError) || value.status !== 400)) setBlocked(true);
  }
  async function mutate(action: "save" | "publish" | "archive") {
    if (busy || blocked || base?.status === "ARCHIVED") return;
    const clean = { slug: input.slug.trim(), title: input.title.trim(), body: input.body.trim() };
    if (action === "save" && ((!base && (clean.slug.length < 3 || clean.slug.length > 100 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(clean.slug))) || clean.title.length < 5 || clean.title.length > 200 || clean.body.length < 10 || clean.body.length > 20000)) {
      setError("Use a lowercase hyphenated slug (3-100 characters), a title of 5-200 characters, and a body of 10-20000 characters after trimming."); return;
    }
    if (action !== "save" && (!base || dirty)) return;
    setBusy(true); setError(""); setMessage("");
    try {
      const result = action === "save" ? base ? await saveArticle(base, clean) : await createArticle(clean) : await transitionArticle(base!, action);
      if (!alive.current) return;
      setBase(result); setInput({ slug: result.slug, title: result.title, body: result.body });
      setMessage(action === "save" ? "Article saved." : action === "publish" ? "Article published." : "Article archived.");
      void client.invalidateQueries({ queryKey: ["adminArticles", actorId] });
      void client.invalidateQueries({ queryKey: ["articles"] });
      // Prefixes reserve invalidation for the planned search and summary views too.
      void client.invalidateQueries({ queryKey: ["search"] });
      void client.invalidateQueries({ queryKey: ["moderation"] });
      if (!base) navigate(`/admin/articles/${result.id}`, { replace: true });
    } catch (value) { if (alive.current) failure(value); }
    finally { if (alive.current) setBusy(false); }
  }
  async function reload() {
    if (!base) return;
    setBusy(true); setError(""); setMessage(""); setBlocked(true);
    controller.current = new AbortController();
    try {
      const result = await getAdminArticle(base.id, controller.current.signal);
      if (alive.current) setLatest(result);
    } catch (value) { if (alive.current) failure(value); }
    finally { if (alive.current) setBusy(false); }
  }
  function reconcile(keep: boolean) {
    if (!latest) return;
    setBase(latest);
    if (!keep) setInput({ slug: latest.slug, title: latest.title, body: latest.body });
    setLatest(undefined); setBlocked(false); setError(""); setMessage("Review complete. Submit again when ready.");
  }
  if (denied) return <section className="board-page"><h1>Article administration is restricted.</h1><p>Your draft is no longer available in this account.</p></section>;
  return <section className="board-page"><Link className="text-link" to="/admin/articles">Back to articles</Link><h1>{base ? "Edit article" : "Create article"}</h1>
    {base && <><p className="subtle-badge">{base.status}</p><p>Original author: {base.author.displayName}</p></>}
    {base?.status === "PUBLISHED" && <p className="archive-notice">This article is published. Saving changes updates the public article immediately.</p>}
    {base?.status === "ARCHIVED" && <p className="archive-notice">This article is archived and read-only. It cannot be republished.</p>}
    {base?.status === "PUBLISHED" && <Link className="text-link" to={`/knowledge/${base.slug}`}>View public article</Link>}
    {error && <p role="alert" className="form-error">{error}</p>}{message && <p role="status">{message}</p>}
    <form className="auth-form article-form" onSubmit={(e) => { e.preventDefault(); void mutate("save"); }}>
      <label htmlFor="article-slug">Article slug</label><input id="article-slug" value={input.slug} readOnly={!!base} disabled={busy} onChange={(e) => setInput({ ...input, slug: e.target.value })} required />
      <p>{base ? "The article URL cannot be changed." : "Use lowercase words separated by hyphens. The URL cannot be changed later."}</p>
      <label htmlFor="article-title">Article title</label><input id="article-title" value={input.title} disabled={busy} readOnly={base?.status === "ARCHIVED"} onChange={(e) => setInput({ ...input, title: e.target.value })} required />
      <label htmlFor="article-body">Article body</label><textarea id="article-body" rows={12} value={input.body} disabled={busy} readOnly={base?.status === "ARCHIVED"} onChange={(e) => setInput({ ...input, body: e.target.value })} required />
      <p>Plain text. Title: 5-200 characters; body: 10-20000 characters after trimming.</p>
      {base?.status !== "ARCHIVED" && <button className="button button-primary" disabled={busy || blocked} type="submit">{busy ? "Working..." : base ? "Save article" : "Create draft"}</button>}
    </form>
    {base && <div className="article-actions">
      {base.status === "DRAFT" && <button className="button button-primary" disabled={busy || blocked || dirty} onClick={() => void mutate("publish")}>Publish article</button>}
      {base.status !== "ARCHIVED" && <button className="button button-secondary" disabled={busy || blocked || dirty} onClick={() => void mutate("archive")}>Archive article</button>}
      <button className="button button-secondary" disabled={busy} onClick={() => void reload()}>Load latest article</button>
      {dirty && base.status !== "ARCHIVED" && <p>Save your changes before publishing or archiving.</p>}
      {base.status !== "ARCHIVED" && <p>Archiving removes the public article permanently from this workflow; it cannot be republished.</p>}
    </div>}
    {blocked && <p role="status">Your draft is preserved. Load the latest article and review it before submitting again.</p>}
    {latest && <section className="reply-card" aria-label="Latest server article"><h2>Latest server article</h2><p>{latest.status}</p><h3>{latest.title}</h3><div className="question-body">{latest.body}</div>
      <div className="article-actions"><button className="button button-secondary" onClick={() => reconcile(false)}>Use server copy</button>
        {latest.status !== "ARCHIVED" && <button className="button button-secondary" onClick={() => reconcile(true)}>Keep my draft after review</button>}</div>
    </section>}
  </section>;
}
