import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { createBoard, getBoard, listBoards, updateBoard } from "./api";
import type { Board } from "./api";

export function AdminBoards() {
  const { user, sessionError } = useAuth();
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
      <section className="about-page">
        <h1>Sign in to manage boards.</h1>
        <Link className="button button-primary" to="/login">
          Sign in
        </Link>
      </section>
    );
  if (user.role !== "ADMINISTRATOR")
    return (
      <section className="about-page">
        <h1>Administrator access required.</h1>
        <p>Your account cannot manage boards.</p>
        <Link to="/" className="text-link">
          Back to the community
        </Link>
      </section>
    );
  return <BoardManagement key={user.id} />;
}

function BoardManagement() {
  const client = useQueryClient();
  const boards = useQuery({
    queryKey: ["boards"],
    queryFn: ({ signal }) => listBoards(signal),
    retry: false,
  });
  const [selected, setSelected] = useState<Board | null>(null);
  const [revision, setRevision] = useState(0);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  async function saved() {
    await client.invalidateQueries({ queryKey: ["boards"] });
    setMessage(selected ? "Board changes saved." : "Board created.");
    setSelected(null);
    setRevision((value) => value + 1);
  }
  function select(board: Board | null) {
    setSelected(board);
    setMessage("");
    setRevision((value) => value + 1);
  }
  return (
    <section className="board-page">
      <p className="eyebrow">COMMUNITY ADMINISTRATION</p>
      <h1>Make room for good questions.</h1>
      <p>
        Create a board, update its details, or archive a space while keeping it
        readable.
      </p>
      {message && (
        <p role="status" className="success-notice">
          {message}
        </p>
      )}
      <div className="board-admin-grid">
        <div className="board-admin-list">
          <h2>Your boards</h2>
          {boards.isPending ? (
            <p role="status">Loading boards...</p>
          ) : boards.isError ? (
            <div role="alert">
              <p>Could not load boards.</p>
              <button
                className="button button-secondary"
                disabled={boards.isFetching}
                onClick={() => void boards.refetch()}
              >
                Retry boards
              </button>
            </div>
          ) : boards.data.length === 0 ? (
            <p>No boards yet. Create the first one.</p>
          ) : (
            boards.data.map((board) => (
              <article key={board.id} className="board-card">
                <h3>{board.name}</h3>
                <p>{board.archived ? "Archived" : "Open"}</p>
                <div className="board-actions">
                  <button
                    className="button button-secondary"
                    disabled={busy}
                    onClick={() => select(board)}
                    aria-label={"Edit " + board.name}
                  >
                    Edit board
                  </button>
                  <Link className="text-link" to={"/boards/" + board.id}>
                    View board
                  </Link>
                </div>
              </article>
            ))
          )}
        </div>
        <BoardEditor
          key={revision}
          board={selected}
          busy={busy}
          onBusy={setBusy}
          onSaved={saved}
          onCancel={() => select(null)}
          onReload={(board) => select(board)}
        />
      </div>
    </section>
  );
}

function BoardEditor({
  board,
  busy,
  onBusy,
  onSaved,
  onCancel,
  onReload,
}: {
  board: Board | null;
  busy: boolean;
  onBusy: (busy: boolean) => void;
  onSaved: () => Promise<void>;
  onCancel: () => void;
  onReload: (board: Board) => void;
}) {
  const [name, setName] = useState(board?.name ?? "");
  const [slug, setSlug] = useState(board?.slug ?? "");
  const [description, setDescription] = useState(board?.description ?? "");
  const [archived, setArchived] = useState(board?.archived ?? false);
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [conflict, setConflict] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    onBusy(true);
    setError("");
    setFields({});
    setConflict(false);
    try {
      if (board)
        await updateBoard(board, { name, slug, description, archived });
      else await createBoard({ name, slug, description });
      await onSaved();
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "The board could not be saved.",
      );
      if (failure instanceof ApiError) {
        setFields(failure.fieldErrors ?? {});
        setConflict(failure.status === 409 && !!board);
      }
    } finally {
      onBusy(false);
    }
  }
  async function reload() {
    if (!board || busy) return;
    onBusy(true);
    try {
      onReload(await getBoard(board.id));
    } catch {
      setError("Could not reload the board. Your draft is still here.");
    } finally {
      onBusy(false);
    }
  }
  return (
    <form
      className="auth-form board-form"
      onSubmit={(event) => void submit(event)}
    >
      <h2>{board ? "Edit board" : "Create a board"}</h2>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {conflict && (
        <button
          type="button"
          className="button button-secondary"
          disabled={busy}
          onClick={() => void reload()}
        >
          Reload latest and discard draft
        </button>
      )}
      <label htmlFor="board-name">Board name</label>
      <input
        id="board-name"
        required
        maxLength={120}
        value={name}
        onChange={(event) => setName(event.target.value)}
        aria-invalid={!!fields.name}
        aria-describedby={fields.name ? "board-name-error" : undefined}
      />
      {fields.name && (
        <p id="board-name-error" className="field-error">
          {fields.name}
        </p>
      )}
      <label htmlFor="board-slug">Slug</label>
      <input
        id="board-slug"
        required
        maxLength={80}
        pattern="[a-z0-9]+(-[a-z0-9]+)*"
        value={slug}
        onChange={(event) => setSlug(event.target.value)}
        aria-invalid={!!fields.slug}
        aria-describedby="board-slug-help"
      />
      <p
        id="board-slug-help"
        className={fields.slug ? "field-error" : "auth-caption"}
      >
        {fields.slug ??
          "A unique short name: lowercase letters, numbers, and single hyphens."}
      </p>
      <label htmlFor="board-description">Description</label>
      <textarea
        id="board-description"
        required
        maxLength={2000}
        rows={5}
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        aria-invalid={!!fields.description}
        aria-describedby={
          fields.description ? "board-description-error" : undefined
        }
      />
      {fields.description && (
        <p id="board-description-error" className="field-error">
          {fields.description}
        </p>
      )}
      {board && (
        <label className="archive-choice">
          <input
            type="checkbox"
            checked={archived}
            onChange={(event) => setArchived(event.target.checked)}
          />
          Archived (readable, closed to new activity)
        </label>
      )}
      <button className="button button-primary" disabled={busy} type="submit">
        {busy ? "Saving..." : board ? "Save changes" : "Create board"}
      </button>
      {board && (
        <button
          className="button button-secondary"
          disabled={busy}
          type="button"
          onClick={onCancel}
        >
          Cancel edit
        </button>
      )}
    </form>
  );
}
