import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../../lib/http";
import { ContentView } from "./ModerationPage";
import { getActions, getContent, restoreContent } from "./reviewApi";
import type { ContentContext } from "./reviewApi";

export function ModerationContentPage({ reply }: { reply: boolean }) {
  const { user, sessionError } = useAuth();
  const { contentId = "" } = useParams();
  if (user === undefined) return <p role="status">{sessionError ? "Account connection unavailable. Reload to try again." : "Checking your account..."}</p>;
  if (!user) return <section className="board-page"><h1>Sign in to review content.</h1><Link to="/login">Sign in</Link></section>;
  if (!["MODERATOR", "ADMINISTRATOR"].includes(user.role)) return <section className="board-page"><h1>Content review is restricted.</h1></section>;
  return <Content key={`${user.id}:${reply}:${contentId}`} id={contentId} reply={reply} actorId={user.id} />;
}
function Content({ id, reply, actorId }: { id: string; reply: boolean; actorId: string }) {
  const query = useQuery({ queryKey: ["moderation", actorId, "content", reply, id],
    queryFn: ({ signal }) => getContent(id, reply, signal), retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <section className="board-page"><Link to="/moderation" className="text-link">Back to reports</Link><h1>Content history and restoration</h1>
    {query.isPending ? <p role="status">Loading content...</p> : query.isError ? <div role="alert" className="form-error">
      <p>{query.error.message}</p><button className="button button-secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Reload content</button>
    </div> : <Restoration initial={query.data} actorId={actorId} />}
  </section>;
}
function Restoration({ initial, actorId }: { initial: ContentContext; actorId: string }) {
  const [context, setContext] = useState(initial);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const [error, setError] = useState("");
  const [denied, setDenied] = useState(false);
  const alive = useRef(true);
  const client = useQueryClient();
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  function failure(value: unknown) {
    setError(value instanceof Error ? value.message : "Could not update content.");
    setMustReload(true);
    if (value instanceof ApiError && [401, 403].includes(value.status ?? 0)) setDenied(true);
  }
  async function reload() {
    setBusy(true); setError("");
    try {
      const fresh = await getContent(context.targetId, context.reply !== null);
      if (!alive.current) return;
      setContext(fresh); setMustReload(false);
      void client.invalidateQueries({ queryKey: ["moderation", actorId, "actions"] });
    } catch (value) { if (alive.current) failure(value); }
    finally { if (alive.current) setBusy(false); }
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy || mustReload) return;
    if (reason.trim().length < 5 || reason.trim().length > 2000) { setError("Use 5 to 2000 characters for the restoration reason."); return; }
    setBusy(true); setError("");
    try {
      const result = await restoreContent(context, reason);
      if (!alive.current) return;
      setContext(result); setReason("");
      void client.invalidateQueries({ queryKey: ["moderation", actorId] });
      void client.invalidateQueries({ queryKey: ["search"] });
      void client.invalidateQueries({ queryKey: ["questions"] });
      void client.invalidateQueries({ queryKey: ["replies", context.question.id] });
      void client.invalidateQueries({ queryKey: ["boards"] });
    } catch (value) { if (alive.current) failure(value); }
    finally { if (alive.current) setBusy(false); }
  }
  if (denied) return <p role="alert">Your account no longer has access to content review.</p>;
  const hidden = (context.reply ?? context.question).visibility === "HIDDEN";
  return <><ContentView context={context} />
    {error && <p role="alert" className="form-error">{error}</p>}
    {hidden ? <form className="auth-form moderation-form" onSubmit={(event) => void submit(event)}>
      <h2>Restore {context.reply ? "reply" : "question"}</h2>
      <p>{context.reply ? "Restoring a reply does not select it as the accepted answer." : "Restoring this question preserves each reply's own visibility and any retained accepted selection."}</p>
      {context.reply && context.question.visibility === "HIDDEN" && <p>The parent question is hidden. This reply will remain private until its parent is restored.</p>}
      <label htmlFor="restore-reason">Restoration reason</label>
      <textarea id="restore-reason" rows={5} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} required />
      <p>5-2000 characters after trimming. Visible only to moderators and administrators.</p>
      {mustReload && <p role="status">Your reason is preserved. Reload and review the current content before trying again.</p>}
      <button className="button" disabled={busy || mustReload} type="submit">{busy ? "Working..." : "Restore content"}</button>
    </form> : <p role="status">The target's own visibility is visible. No restoration is needed.</p>}
    <button className="button button-secondary" disabled={busy} onClick={() => void reload()}>Reload content context</button>
    <History id={context.targetId} reply={context.reply !== null} actorId={actorId} />
  </>;
}
function History({ id, reply, actorId }: { id: string; reply: boolean; actorId: string }) {
  const [page, setPage] = useState(0);
  const query = useQuery({ queryKey: ["moderation", actorId, "actions", reply, id, page],
    queryFn: ({ signal }) => getActions(id, reply, page, signal), retry: false });
  return <section aria-label="Visibility history"><h2>Visibility history</h2><p>Content changes only. Report decisions remain in their report details.</p>
    {query.isPending ? <p role="status">Loading history...</p> : query.isError ? <div role="alert"><p>{query.error.message}</p>
      <button className="button button-secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Retry history</button></div> : <>
      {query.data.items.length === 0 ? <p>No visibility changes on this page.</p> : <ol className="moderation-list">{query.data.items.map((action) =>
        <li className="reply-card" key={action.id}><p>{action.action === "HIDE" ? "Hidden" : "Restored"} by {action.actor.displayName}</p>
          <time dateTime={action.createdAt}>{new Date(action.createdAt).toLocaleString()}</time><p className="moderation-content">{action.reason}</p></li>)}</ol>}
      {(query.data.totalPages > 1 || page > 0) && <nav className="pagination" aria-label="History pages">
        <button className="button button-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous actions</button>
        <span>Page {page + 1} of {Math.max(1, query.data.totalPages)}</span>
        <button className="button button-secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage(page + 1)}>Next actions</button>
      </nav>}
    </>}
  </section>;
}
