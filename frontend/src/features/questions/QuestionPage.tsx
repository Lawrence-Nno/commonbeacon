import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { getQuestion, updateQuestion } from "./api";
import type { Question, QuestionInput } from "./api";
import { Replies } from "../replies/Replies";
import { QuestionForm } from "./QuestionForm";

export function QuestionPage({ editing = false }: { editing?: boolean }) {
  const { questionId = "" } = useParams();
  const { user, sessionError } = useAuth();
  const query = useQuery({
    queryKey: ["questions", questionId],
    queryFn: ({ signal }) => getQuestion(questionId, signal),
    retry: false,
    refetchOnWindowFocus: !editing,
  });
  if (query.isPending) return <p role="status">Loading question...</p>;
  if (query.isError) {
    const missing =
      query.error instanceof ApiError &&
      (query.error.status === 404 || query.error.status === 400);
    return (
      <section className="board-page">
        <h1>{missing ? "Question not found." : "Question unavailable."}</h1>
        <p>
          {missing
            ? "This question is not available to read."
            : "We could not load this question. Please try again."}
        </p>
        {!missing && (
          <button
            className="button button-secondary"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            Retry question
          </button>
        )}
        <Link className="text-link" to="/">
          Back to the community
        </Link>
      </section>
    );
  }
  const question = query.data;
  if (editing) {
    if (user === undefined)
      return (
        <p role="status">
          {sessionError
            ? "Account connection unavailable. Reload to try again."
            : "Checking your account..."}
        </p>
      );
    if (!user)
      return (
        <section className="board-page">
          <h1>Sign in to edit your question.</h1>
          <Link className="button button-primary" to="/login">
            Sign in
          </Link>
        </section>
      );
    if (user.id !== question.author.id)
      return (
        <section className="board-page">
          <h1>Only the author can edit this question.</h1>
          <Link className="text-link" to={"/questions/" + questionId}>
            Back to the question
          </Link>
        </section>
      );
    return <OwnerEditor key={user.id + questionId} question={question} />;
  }
  return (
    <article className="board-page question-page">
      <Link className="text-link" to={"/boards/" + question.board.id}>
        ← {question.board.name}
      </Link>
      <p className="eyebrow">A QUESTION FOR THE COMMUNITY</p>
      <h1>{question.title}</h1>
      <p className="question-meta">
        Asked by {question.author.displayName} <span aria-hidden="true">·</span>{" "}
        <time dateTime={question.createdAt}>
          {new Date(question.createdAt).toLocaleString()}
        </time>
        {question.version > 0 && (
          <>
            {" "}
            · Edited{" "}
            <time dateTime={question.updatedAt}>
              {new Date(question.updatedAt).toLocaleString()}
            </time>
          </>
        )}
      </p>
      {question.board.archived && (
        <p className="archive-notice">
          This board is archived. You can read this question, but changes are
          closed.
        </p>
      )}
      <div className="question-body">{question.body}</div>
      {user?.id === question.author.id && !question.board.archived && (
        <Link
          className="button button-secondary"
          to={"/questions/" + questionId + "/edit"}
        >
          Edit question
        </Link>
      )}
      <Replies question={question} />
    </article>
  );
}

function OwnerEditor({ question }: { question: Question }) {
  // Keep the version attached to this draft until the user explicitly reloads.
  const [base, setBase] = useState(question);
  const client = useQueryClient();
  const navigate = useNavigate();
  async function save(input: QuestionInput) {
    const updated = await updateQuestion(base, input);
    client.setQueryData(["questions", updated.id], updated);
    await client.invalidateQueries({
      queryKey: ["questions", "board", base.board.id],
    });
    navigate("/questions/" + updated.id, { replace: true });
  }
  async function reload() {
    const latest = await getQuestion(base.id);
    setBase(latest);
    return { title: latest.title, body: latest.body };
  }
  return (
    <section className="board-page">
      <Link className="text-link" to={"/questions/" + base.id}>
        ← Back to the question
      </Link>
      <h1>Edit your question.</h1>
      <QuestionForm
        initial={base}
        editing
        closed={base.board.archived}
        onSave={save}
        onReload={reload}
      />
    </section>
  );
}
