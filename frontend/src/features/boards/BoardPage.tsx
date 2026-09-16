import { QuestionList } from "../questions/QuestionList";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { ApiError } from "../../lib/http";
import { getBoard } from "./api";

export function BoardPage() {
  const { boardId = "" } = useParams();
  const query = useQuery({
    queryKey: ["boards", boardId],
    queryFn: ({ signal }) => getBoard(boardId, signal),
    retry: false,
  });
  if (query.isPending) return <p role="status">Loading board...</p>;
  if (query.isError) {
    const missing =
      query.error instanceof ApiError &&
      (query.error.status === 404 || query.error.status === 400);
    return (
      <section className="about-page">
        <h1>{missing ? "Board not found." : "Board unavailable."}</h1>
        <p>
          {missing
            ? "This board link does not lead to an available board."
            : "We could not load this board. Please try again."}
        </p>
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
  const board = query.data;
  return (
    <section className="board-page">
      <Link className="text-link" to="/">
        ← All boards
      </Link>
      <p className="eyebrow">YOUR COMMUNITY BOARD</p>
      <h1>{board.name}</h1>
      <p className="board-description">{board.description}</p>
      {board.archived && (
        <p className="archive-notice">
          <strong>Archived board.</strong> This space remains readable. New
          questions and replies are closed.
        </p>
      )}
      <QuestionList board={board} />
    </section>
  );
}
