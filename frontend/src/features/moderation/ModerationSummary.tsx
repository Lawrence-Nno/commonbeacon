import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { ApiError, getJson } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";

type Summary = { unansweredQuestions: number; openReports: number; publishedArticles: number };
function decode(value: unknown): Summary {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new ApiError("invalid-response", "The summary response was invalid.");
  const v = value as Record<string, unknown>;
  function count(key: string): number {
    const n = v[key];
    if (typeof n !== "number" || !Number.isSafeInteger(n) || n < 0) throw new ApiError("invalid-response", "The summary response was invalid.");
    return n;
  }
  return { unansweredQuestions: count("unansweredQuestions"), openReports: count("openReports"), publishedArticles: count("publishedArticles") };
}

export function ModerationSummary() {
  const { user } = useAuth();
  if (!user || !["MODERATOR", "ADMINISTRATOR"].includes(user.role)) return null;
  return <SummaryCards key={user.id} actorId={user.id} />;
}
function SummaryCards({ actorId }: { actorId: string }) {
  const query = useQuery({ queryKey: ["moderation", actorId, "summary"],
    queryFn: ({ signal }) => getJson("/api/v1/moderation/summary", decode, signal), retry: false, staleTime: 0 });
  const denied = query.error instanceof ApiError && [401, 403].includes(query.error.status ?? 0);
  return <section aria-label="Operational summary" className="operational-summary"><h2>Community overview</h2>
    {query.isPending ? <p role="status">Loading community overview...</p>
      : query.isError ? <div role="alert" className="form-error"><p>{denied ? "Your account cannot access the community overview." : "Could not load the community overview."}</p>
        {!denied && <button className="button button-secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Retry overview</button>}</div>
      : <><dl className="summary-cards">
        <div><dt>Unanswered questions</dt><dd>{query.data.unansweredQuestions}</dd><p>Visible questions without a visible accepted answer, including archived boards.</p><Link className="text-link" to="/">Browse boards</Link></div>
        <div><dt>Open reports</dt><dd>{query.data.openReports}</dd><p>Each open report counts, including separate reports about the same content.</p><Link className="text-link" to="/moderation?status=OPEN&page=0">Review open reports</Link></div>
        <div><dt>Published articles</dt><dd>{query.data.publishedArticles}</dd><p>Articles currently available in the public knowledge library.</p><Link className="text-link" to="/knowledge">Browse published articles</Link></div>
      </dl><p className="auth-caption">Counts refresh when you return to this page. They are not live updates from other sessions.</p></>}
  </section>;
}
