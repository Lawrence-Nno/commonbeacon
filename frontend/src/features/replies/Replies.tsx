import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { getQuestion } from "../questions/api";
import type { Question } from "../questions/api";
import { createReply, getReply, listReplies, updateReply } from "./api";
import type { Reply } from "./api";
import { ReplyForm } from "./ReplyForm";

export function Replies({ question }: { question: Question }) {
  const { user } = useAuth();
  // Changing account/thread discards private drafts.
  return (
    <ReplyThread
      key={question.id + (user?.id ?? "visitor")}
      question={question}
    />
  );
}
function ReplyThread({ question }: { question: Question }) {
  const { user } = useAuth();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const raw = params.get("replyPage") ?? "0";
  const page = /^\d+$/.test(raw) ? Number(raw) : -1;
  const valid =
    Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const query = useQuery({
    queryKey: ["replies", question.id, page],
    queryFn: ({ signal }) => listReplies(question.id, page, signal),
    enabled: valid,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const [editing, setEditing] = useState<Reply | null>(null);
  const [message, setMessage] = useState("");
  const [closed, setClosed] = useState(false);
  const unavailable =
    query.error instanceof ApiError && query.error.status === 404;
  const archived = question.board.archived || closed;
  function go(next: number) {
    setParams((current) => {
      const copy = new URLSearchParams(current);
      copy.set("replyPage", String(next));
      return copy;
    });
  }
  async function invalidate() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["replies", question.id] }),
      client.invalidateQueries({ queryKey: ["questions", question.id] }),
      client.invalidateQueries({
        queryKey: ["questions", "board", question.board.id],
      }),
    ]);
  }
  async function post(body: string) {
    await createReply(question.id, body);
    setMessage("Your reply was posted.");
    // Posting already succeeded. A refresh failure must not invite duplicate submission.
    try {
      const latest = await listReplies(question.id, 0);
      go(Math.max(0, latest.totalPages - 1));
    } catch {
      setMessage("Your reply was posted. Refresh the replies to see it.");
    }
    await invalidate();
  }
  async function save(body: string) {
    if (!editing) return;
    await updateReply(editing, body);
    setEditing(null);
    setMessage("Your reply was updated.");
    await invalidate();
  }
  async function reload() {
    if (!editing) throw new Error("No selected reply");
    const latest = await getReply(editing.id);

    const parent = await client.fetchQuery({
      queryKey: ["questions", question.id],
      queryFn: async () => {
        return getQuestion(question.id);
      },
      staleTime: 0,
    });
    setEditing(latest);
    setClosed(parent.board.archived);
    return latest.body;
  }
  return (
    <section className="reply-section" aria-labelledby="replies-heading">
      <div className="section-heading">
        <h2 id="replies-heading">Replies</h2>
        {query.data && (
          <span className="subtle-badge">
            {query.data.totalElements}{" "}
            {query.data.totalElements === 1 ? "reply" : "replies"}
          </span>
        )}
      </div>
      {message && (
        <p className="success-notice" role="status">
          {message}
        </p>
      )}
      {!valid ? (
        <p className="form-error" role="alert">
          This reply page is invalid.{" "}
          <button className="text-link" onClick={() => go(0)}>
            Go to the first page
          </button>
        </p>
      ) : query.isPending ? (
        <p role="status">Loading replies...</p>
      ) : unavailable ? (
        <p className="form-error" role="alert">
          This conversation is no longer available.
        </p>
      ) : query.isError ? (
        <div className="form-error" role="alert">
          <p>Could not load replies.</p>
          <button
            className="button button-secondary"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            Retry replies
          </button>
        </div>
      ) : (
        <>
          {query.data.items.length === 0 ? (
            <div className="empty-state">
              <h3>
                {page === 0
                  ? "Room for the first reply."
                  : "No replies on this page."}
              </h3>
              <p>
                {archived
                  ? "This board is closed to new replies."
                  : "A little shared experience can help someone move forward."}
              </p>
            </div>
          ) : (
            <div className="reply-list">
              {query.data.items.map((reply) => (
                <article
                  className="reply-card"
                  key={reply.id}
                  id={"reply-" + reply.id}
                  aria-label={"Reply by " + reply.author.displayName}
                >
                  <p className="question-meta">
                    <strong>{reply.author.displayName}</strong> ·{" "}
                    <time dateTime={reply.createdAt}>
                      {new Date(reply.createdAt).toLocaleString()}
                    </time>
                    {reply.version > 0 && " · Edited"}
                  </p>
                  <div className="reply-body">{reply.body}</div>
                  {user?.id === reply.author.id && !archived && (
                    <button
                      className="button button-secondary"
                      onClick={() => {
                        setEditing(reply);
                        setMessage("");
                      }}
                    >
                      Edit reply
                    </button>
                  )}
                </article>
              ))}
            </div>
          )}
          {(query.data.totalPages > 1 || page > 0) && (
            <nav className="pagination" aria-label="Reply pages">
              <button
                className="button button-secondary"
                disabled={page === 0}
                onClick={() => go(page - 1)}
              >
                Previous replies
              </button>
              <span>
                Page {page + 1} of {Math.max(1, query.data.totalPages)}
              </span>
              <button
                className="button button-secondary"
                disabled={page + 1 >= query.data.totalPages}
                onClick={() => go(page + 1)}
              >
                Next replies
              </button>
              {page >= query.data.totalPages && (
                <button className="text-link" onClick={() => go(0)}>
                  First page
                </button>
              )}
            </nav>
          )}
        </>
      )}
      {!unavailable &&
        user &&
        (editing ? (
          <ReplyForm
            key={editing.id}
            initial={editing.body}
            editing
            closed={archived}
            onSave={save}
            onReload={reload}
            onCancel={() => setEditing(null)}
          />
        ) : (
          <ReplyForm closed={archived} onSave={post} />
        ))}
      {!unavailable && user === null && !archived && (
        <p>
          <Link className="text-link" to="/login">
            Sign in to reply
          </Link>
        </p>
      )}
      {archived && !user && (
        <p className="archive-notice">
          This board is archived. Replies remain readable.
        </p>
      )}
    </section>
  );
}
