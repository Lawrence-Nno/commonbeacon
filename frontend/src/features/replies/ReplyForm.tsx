import { useId, useState } from "react";
import type { FormEvent } from "react";
import { ApiError } from "../../lib/http";

export function ReplyForm({
  initial = "",
  editing = false,
  closed = false,
  onSave,
  onReload,
  onCancel,
}: {
  initial?: string;
  editing?: boolean;
  closed?: boolean;
  onSave: (body: string) => Promise<void>;
  onReload?: () => Promise<string>;
  onCancel?: () => void;
}) {
  const id = useId();
  const [body, setBody] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [field, setField] = useState("");
  const [conflict, setConflict] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || closed) return;
    setBusy(true);
    setError("");
    setField("");
    setConflict(false);
    try {
      await onSave(body);
      if (!editing) setBody("");
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Could not save your reply.",
      );
      if (failure instanceof ApiError) {
        setField(failure.fieldErrors?.body ?? "");
        setConflict(failure.status === 409);
      }
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    if (!onReload || busy) return;
    setBusy(true);
    try {
      setBody(await onReload());
      setError("");
      setField("");
      setConflict(false);
    } catch {
      setError("Could not reload the reply. Your draft is still here.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="auth-form reply-form"
      onSubmit={(event) => void submit(event)}
    >
      <h3>{editing ? "Edit your reply" : "Add your reply"}</h3>
      {closed && (
        <p className="archive-notice">
          This board is archived. Your draft is visible, but replies cannot be
          saved.
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {conflict && onReload && (
        <button
          type="button"
          className="button button-secondary"
          disabled={busy}
          onClick={() => void reload()}
        >
          Reload latest and discard draft
        </button>
      )}
      <label htmlFor={id}>{editing ? "Edit reply" : "Your reply"}</label>
      <textarea
        id={id}
        required
        minLength={1}
        maxLength={20000}
        rows={6}
        value={body}
        onChange={(event) => setBody(event.target.value)}
        aria-invalid={!!field}
        aria-describedby={id + "-help"}
      />
      <p id={id + "-help"} className={field ? "field-error" : "auth-caption"}>
        {field || "Share a helpful answer. Use 1–20,000 characters."}
      </p>
      <div className="board-actions">
        <button
          className="button button-primary"
          type="submit"
          disabled={busy || closed}
        >
          {busy ? "Saving..." : editing ? "Save reply" : "Post reply"}
        </button>
        {onCancel && (
          <button
            type="button"
            className="button button-secondary"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel edit
          </button>
        )}
      </div>
    </form>
  );
}
