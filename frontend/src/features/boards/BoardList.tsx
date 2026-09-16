import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { listBoards } from "./api";

export function BoardList() {
  const boards = useQuery({
    queryKey: ["boards"],
    queryFn: ({ signal }) => listBoards(signal),
    retry: false,
  });
  if (boards.isPending) return <p role="status">Loading boards...</p>;
  if (boards.isError)
    return (
      <div className="form-error" role="alert">
        <p>We could not load the boards.</p>
        <button
          className="button button-secondary"
          disabled={boards.isFetching}
          onClick={() => void boards.refetch()}
        >
          Retry boards
        </button>
      </div>
    );
  if (!boards.data.length)
    return (
      <div className="empty-state">
        <h3>No boards yet.</h3>
        <p>
          The community is getting ready. Boards will appear here when they are
          available.
        </p>
      </div>
    );
  return (
    <div className="board-list">
      {boards.data.map((board) => (
        <Link key={board.id} className="board-card" to={"/boards/" + board.id}>
          <div className="board-card-heading">
            <h3>{board.name}</h3>
            {board.archived && <span className="subtle-badge">Archived</span>}
          </div>
          <p>{board.description}</p>
          <span className="text-link">
            Visit board <span aria-hidden="true">→</span>
          </span>
        </Link>
      ))}
    </div>
  );
}
