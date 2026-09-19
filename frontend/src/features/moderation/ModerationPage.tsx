import { useQuery } from "@tanstack/react-query";
import { Link, useParams, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../../lib/http";
import { getReport, listReports } from "./reviewApi";
import type { ReportDetail, ReportStatus } from "./reviewApi";

export function ModerationPage() {
  const { user, sessionError } = useAuth();
  const { reportId } = useParams();
  if (user === undefined) return <p role="status">{sessionError ? "Account connection unavailable. Reload to try again." : "Checking your account..."}</p>;
  if (!user) return <section className="board-page"><h1>Sign in to review reports.</h1><Link to="/login" className="text-link">Sign in</Link></section>;
  if (!["MODERATOR", "ADMINISTRATOR"].includes(user.role)) return <section className="board-page"><h1>Report review is restricted.</h1><p>You need moderator or administrator access.</p><Link to="/">Back to the community</Link></section>;
  return reportId ? <Review key={user.id + reportId} id={reportId} actorId={user.id} /> : <Queue key={user.id} actorId={user.id} />;
}

function Failure({ error, retry, busy }: { error: Error; retry: () => void; busy: boolean }) {
  const denied = error instanceof ApiError && (error.status === 401 || error.status === 403);
  const missing = error instanceof ApiError && (error.status === 404 || error.status === 400);
  return <div className="form-error" role="alert">
    <p>{denied ? "Your account no longer has access to report review." : missing ? "This report or request is not available." : "Could not load reports. Please try again."}</p>
    {!denied && !missing && <button className="button button-secondary" disabled={busy} onClick={retry}>Retry reports</button>}
  </div>;
}

function Queue({ actorId }: { actorId: string }) {
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "OPEN";
  const rawPage = params.get("page") ?? "0";
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : -1;
  const valid = (status === "OPEN" || status === "RESOLVED") && Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const query = useQuery({ queryKey: ["moderation", actorId, "reports", status, page],
    queryFn: ({ signal }) => listReports(status as ReportStatus, page, signal), enabled: valid, retry: false });
  function go(next: number, nextStatus = status) { setParams({ status: nextStatus, page: String(next) }); }
  return <section className="board-page">
    <p className="eyebrow">COMMUNITY CARE</p><h1>Report review</h1>
    <p>Private reports, oldest first. Review is currently read-only.</p>
    <div className="question-filter"><label htmlFor="report-status">Report status</label>
      <select id="report-status" value={valid ? status : ""} onChange={(event) => go(0, event.target.value)}>
        {!valid && <option value="" disabled>Choose a status</option>}
        <option value="OPEN">Open</option><option value="RESOLVED">Resolved</option>
      </select>
    </div>
    {!valid ? <p role="alert">Invalid report filters. <button className="text-link" onClick={() => go(0, "OPEN")}>Reset filters</button></p>
      : query.isPending ? <p role="status">Loading reports...</p>
      : query.isError ? <Failure error={query.error} retry={() => void query.refetch()} busy={query.isFetching} />
      : <>
        <p>{query.data.totalElements} {status.toLowerCase()} reports</p>
        {query.data.items.length === 0 ? <p className="empty-state">{page === 0 ? "No reports with this status." : "No reports on this page."}</p>
          : <ol className="moderation-list">{query.data.items.map((report) => <li key={report.id} className="reply-card">
            <Link className="text-link" to={`/moderation/reports/${report.id}?status=${status}&page=${page}`}>Review {report.targetKind.toLowerCase()} report</Link>
            <p className="question-meta">Reported by {report.reporter.displayName} · <time dateTime={report.createdAt}>{new Date(report.createdAt).toLocaleString()}</time></p>
            <p className="moderation-content">{Array.from(report.reason).slice(0, 180).join("")}{Array.from(report.reason).length > 180 ? "…" : ""}</p>
          </li>)}</ol>}
        {(query.data.totalPages > 1 || page > 0) && <nav className="pagination" aria-label="Report pages">
          <button className="button button-secondary" disabled={page === 0} onClick={() => go(page - 1)}>Previous reports</button>
          <span>Page {page + 1} of {Math.max(1, query.data.totalPages)}</span>
          <button className="button button-secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => go(page + 1)}>Next reports</button>
          {page >= query.data.totalPages && <button className="text-link" onClick={() => go(0)}>First page</button>}
        </nav>}
      </>}
  </section>;
}

function Review({ id, actorId }: { id: string; actorId: string }) {
  const [params] = useSearchParams();
  const query = useQuery({ queryKey: ["moderation", actorId, "report", id], queryFn: ({ signal }) => getReport(id, signal), retry: false });
  return <section className="board-page">
    <Link className="text-link" to={"/moderation?" + params.toString()}>Back to reports</Link>
    <h1>Report details</h1>
    {query.isPending ? <p role="status">Loading report context...</p> : query.isError
      ? <Failure error={query.error} retry={() => void query.refetch()} busy={query.isFetching} />
      : <Detail data={query.data} />}
  </section>;
}

function Detail({ data: { report, context } }: { data: ReportDetail }) {
  return <>
    <p className="subtle-badge">{report.status === "OPEN" ? "Open report" : "Resolved report"}</p>
    <p>Reported by {report.reporter.displayName} · <time dateTime={report.createdAt}>{new Date(report.createdAt).toLocaleString()}</time></p>
    <h2>Report reason</h2><p className="moderation-content">{report.reason}</p>
    {report.resolver && <section aria-label="Resolution"><h2>Resolution</h2><p>{report.resolutionDecision?.replaceAll("_", " ")} · {report.resolver.displayName}</p>
      {report.resolvedAt && <time dateTime={report.resolvedAt}>{new Date(report.resolvedAt).toLocaleString()}</time>}
      <p className="moderation-content">{report.resolutionNote}</p></section>}
    <p>Board: {context.board.name}{context.board.archived && " (archived)"}</p>
    <p>{context.effectivePublicVisibility ? "The reported content is publicly visible." : "The reported content is not publicly visible."}</p>
    <section className="reply-card" aria-label="Question context">
      <h2>{context.question.title}</h2><p>Question by {context.question.author.displayName} · {context.question.visibility.toLowerCase()}</p>
      <p className="moderation-content">{context.question.body}</p>
      {context.question.acceptedReplyId && <p>An accepted reply is retained for this question.</p>}
      {context.question.visibility === "VISIBLE" && <Link className="text-link" to={"/questions/" + context.question.id}>View public question</Link>}
    </section>
    {context.reply && <section className="reply-card" aria-label="Reported reply context"><h2>Reported reply</h2>
      <p>By {context.reply.author.displayName} · {context.reply.visibility.toLowerCase()}</p><p className="moderation-content">{context.reply.body}</p>
      {context.reply.visibility === "VISIBLE" && context.question.visibility === "HIDDEN" && <p>This reply is visible internally, but its hidden question keeps it private.</p>}
    </section>}
    <p className="availability-note">Report review is currently read-only.</p>
  </>;
}
