import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import type { Board } from "../boards/api";
import { listQuestions } from "./api";

export function QuestionList({ board }: { board: Board }) {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "all";
  const validStatus = ["all", "solved", "unanswered"].includes(status);
  const pageLink = (next: number) => "?page=" + next + (status === "all" ? "" : "&status=" + status);
  const rawPage = params.get("page") ?? "0";
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : -1;
  const validPage =
    Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const query = useQuery({
    queryKey: ["questions", "board", board.id, page, status],
    queryFn: ({ signal }) => listQuestions(board.id, page, signal, status),
    enabled: validPage && validStatus,
    retry: false,
  });
  return (
    <section
      className="question-list-section"
      aria-labelledby="questions-heading"
    >
      <div className="section-heading">
        <h2 id="questions-heading">Questions</h2>
        {!board.archived &&
          (user ? (
            <Link
              className="button button-primary"
              to={"/boards/" + board.id + "/questions/new"}
            >
              Ask a question
            </Link>
          ) : user === null ? (
            <Link className="text-link" to="/login">
              Sign in to ask a question
            </Link>
          ) : null)}
      </div>
      <label className="question-filter">Show questions
        <select value={validStatus ? status : "all"} onChange={(event) => {
          const next = new URLSearchParams(params);
          next.set("status", event.target.value);
          next.set("page", "0");
          setParams(next);
        }}>
          <option value="all">All questions</option>
          <option value="solved">Solved</option>
          <option value="unanswered">Unanswered</option>
        </select>
      </label>
      {!validStatus ? <p role="alert">This question filter is invalid. Choose a filter above.</p> : !validPage ? (
        <div role="alert" className="form-error">
          This page number is invalid.{" "}
          <Link to={"/boards/" + board.id}>Go to the first page</Link>
        </div>
      ) : query.isPending ? (
        <p role="status">Loading questions...</p>
      ) : query.isError ? (
        <div role="alert" className="form-error">
          <p>We could not load the questions.</p>
          <button
            className="button button-secondary"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            Retry questions
          </button>
        </div>
      ) : (
        <>
          <p className="question-count">
            {query.data.totalElements}{" "}
            {query.data.totalElements === 1 ? "question" : "questions"}
          </p>
          {query.data.items.length === 0 ? (
            <div className="empty-state">
              <h3>
                {page === 0
                  ? status === "all" ? "The first question is still ahead." : "No questions match this filter."
                  : "No questions on this page."}
              </h3>
              <p>
                {board.archived
                  ? "There are no questions to read here."
                  : page === 0
                    ? "Have a question? Share what you have tried and what you would like to learn."
                    : "Try an earlier page."}
              </p>
            </div>
          ) : (
            <div className="question-list">
              {query.data.items.map((question) => (
                <article className="question-card" key={question.id}>
                  {question.solved && <span className="subtle-badge">Solved</span>}
                  <h3>
                    <Link to={"/questions/" + question.id}>
                      {question.title}
                    </Link>
                  </h3>
                  <p className="question-meta">
                    {question.author.displayName}{" "}
                    <span aria-hidden="true">·</span>{" "}
                    <time dateTime={question.createdAt}>
                      {new Date(question.createdAt).toLocaleDateString()}
                    </time>
                  </p>
                </article>
              ))}
            </div>
          )}
          {(query.data.totalPages > 1 || page > 0) && (
            <nav aria-label="Question pages" className="pagination">
              {page > 0 && (
                <Link
                  className="button button-secondary"
                  to={pageLink(page - 1)}
                >
                  Previous page
                </Link>
              )}
              <span>
                Page {page + 1} of {Math.max(query.data.totalPages, 1)}
              </span>
              {page + 1 < query.data.totalPages && (
                <Link
                  className="button button-secondary"
                  to={pageLink(page + 1)}
                >
                  Next page
                </Link>
              )}
              {page >= query.data.totalPages && (
                <Link className="text-link" to={pageLink(0)}>
                  First page
                </Link>
              )}
            </nav>
          )}
        </>
      )}
    </section>
  );
}
