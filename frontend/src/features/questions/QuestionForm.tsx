import { useState } from "react";
import type { FormEvent } from "react";
import { ApiError } from "../../lib/http";
import type { QuestionInput } from "./api";

export function QuestionForm({
  initial,
  editing = false,
  closed = false,
  onSave,
  onReload,
}: {
  initial: QuestionInput;
  editing?: boolean;
  closed?: boolean;
  onSave: (input: QuestionInput) => Promise<void>;
  onReload?: () => Promise<QuestionInput>;
}) {
  const [title, setTitle] = useState(initial.title);
  const [body, setBody] = useState(initial.body);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [conflict, setConflict] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || closed) return;
    setBusy(true);
    setError("");
    setFields({});
    setConflict(false);
    try {
      await onSave({ title, body });
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "The question could not be saved.",
      );
      if (failure instanceof ApiError) {
        setFields(failure.fieldErrors ?? {});
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
      const latest = await onReload();
      setTitle(latest.title);
      setBody(latest.body);
      setError("");
      setFields({});
      setConflict(false);
    } catch {
      setError("Could not reload the question. Your draft is still here.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="auth-form question-form"
      onSubmit={(event) => void submit(event)}
    >
      {closed && (
        <p className="archive-notice">
          This board is archived. Your draft is visible, but changes cannot be
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
      <label htmlFor="question-title">Question title</label>
      <input
        id="question-title"
        required
        minLength={5}
        maxLength={200}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        aria-invalid={!!fields.title}
        aria-describedby="question-title-help"
      />
      <p
        id="question-title-help"
        className={fields.title ? "field-error" : "auth-caption"}
      >
        {fields.title ?? "Summarize your question in 5–200 characters."}
      </p>
      <label htmlFor="question-body">Details</label>
      <textarea
        id="question-body"
        required
        minLength={10}
        maxLength={20000}
        rows={10}
        value={body}
        onChange={(event) => setBody(event.target.value)}
        aria-invalid={!!fields.body}
        aria-describedby="question-body-help"
      />
      <p
        id="question-body-help"
        className={fields.body ? "field-error" : "auth-caption"}
      >
        {fields.body ??
          "Include what you tried and what happened. Use 10–20,000 characters."}
      </p>
      <button
        className="button button-primary"
        type="submit"
        disabled={busy || closed}
      >
        {busy ? "Saving..." : editing ? "Save question" : "Publish question"}
      </button>
    </form>
  );
}
