import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { getBoard } from "../boards/api";
import { ApiError } from "../../lib/http";
import { createQuestion } from "./api";
import type { QuestionInput } from "./api";
import { QuestionForm } from "./QuestionForm";

export function NewQuestionPage() {
  const { boardId = "" } = useParams();
  const { user, sessionError } = useAuth();
  const client = useQueryClient();
  const navigate = useNavigate();
  const query = useQuery({
    queryKey: ["boards", boardId],
    queryFn: ({ signal }) => getBoard(boardId, signal),
    retry: false,
  });
  if (query.isPending || (user === undefined && !sessionError))
    return <p role="status">Loading question form...</p>;
  if (query.isError) {
    const missing =
      query.error instanceof ApiError &&
      (query.error.status === 400 || query.error.status === 404);
    return (
      <section className="board-page">
        <h1>{missing ? "Board not found." : "Board unavailable."}</h1>
        {!missing && (
          <button
            className="button button-secondary"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            Retry board
          </button>
        )}
        <Link className="text-link" to="/">
          Back to the community
        </Link>
      </section>
    );
  }
  if (sessionError && user === undefined)
    return (
      <p role="alert">
        We could not check your account. Reload the page to try again.
      </p>
    );
  if (!user)
    return (
      <section className="board-page">
        <h1>Sign in to ask a question.</h1>
        <Link className="button button-primary" to="/login">
          Sign in
        </Link>
      </section>
    );
  if (query.data.archived)
    return (
      <section className="board-page">
        <h1>This board is archived.</h1>
        <p>New questions are closed.</p>
        <Link className="text-link" to={"/boards/" + boardId}>
          Back to the board
        </Link>
      </section>
    );
  async function save(input: QuestionInput) {
    const question = await createQuestion(boardId, input);
    client.setQueryData(["questions", question.id], question);
    await client.invalidateQueries({
      queryKey: ["questions", "board", boardId],
    });
    navigate("/questions/" + question.id, { replace: true });
  }
  return (
    <section className="board-page">
      <Link className="text-link" to={"/boards/" + boardId}>
        ← {query.data.name}
      </Link>
      <p className="eyebrow">LET'S FIGURE IT OUT TOGETHER</p>
      <h1>Ask the community.</h1>
      <QuestionForm
        key={user.id + boardId}
        initial={{ title: "", body: "" }}
        onSave={save}
      />
    </section>
  );
}
