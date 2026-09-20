import { useEffect, useId, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { createReport } from "./api";
import type { ReportTarget } from "./api";

export function ReportControl({ target }: { target: ReportTarget }) {
  const { user } = useAuth();
  if (!user) return null;
  // Unmount private draft/result state when the account or content changes.
  return <ReportForm key={user.id + (target.questionId ?? target.replyId)} target={target} />;
}

function ReportForm({ target }: { target: ReportTarget }) {
  const client = useQueryClient();
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [field, setField] = useState("");
  const [sent, setSent] = useState(false);
  const label = target.questionId ? "Report question" : "Report reply";

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current) return;
    const trimmed = reason.trim();
    setError("");
    setField("");
    if (trimmed.length < 5 || trimmed.length > 2000) {
      setField("Use 5–2,000 characters after trimming.");
      return;
    }
    pending.current = true;
    setBusy(true);
    try {
      await createReport(target, trimmed);
      if (!alive.current) return;
      void client.invalidateQueries({ queryKey: ["moderation"] });
      setSent(true);
      setReason("");
      setOpen(false);
    } catch (failure) {
      if (!alive.current) return;
      setError(failure instanceof Error ? failure.message : "Could not submit your report.");
      if (failure instanceof ApiError) setField(failure.fieldErrors?.reason ?? "");
    } finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  return (
    <div className="report-control">
      {sent ? <p className="success-notice" role="status">Your report was submitted.</p> : (
        <button ref={trigger} type="button" className="button button-secondary"
          aria-expanded={open} aria-controls={id + "-form"}
          onClick={() => setOpen(!open)} disabled={busy}>{label}</button>
      )}
      {open && (
        <form id={id + "-form"} className="auth-form reply-form" aria-label={label}
          onSubmit={(event) => void submit(event)}>
          <label htmlFor={id}>Reason for reporting</label>
          <textarea id={id} value={reason} required minLength={5} maxLength={2000}
            rows={3} disabled={busy} aria-invalid={!!field} aria-describedby={id + "-help"}
            onChange={(event) => setReason(event.target.value)} />
          <p id={id + "-help"} className={field ? "field-error" : "auth-caption"}>
            {field || "Explain the concern in 5–2,000 characters. Your report is not public."}
          </p>
          {error && <p className="form-error" role="alert">{error}</p>}
          <div className="board-actions">
            <button type="submit" className="button button-primary" disabled={busy}>
              {busy ? "Submitting report..." : "Submit report"}
            </button>
            <button type="button" className="button button-secondary" disabled={busy}
              onClick={() => { setOpen(false); trigger.current?.focus(); }}>Cancel report</button>
          </div>
        </form>
      )}
    </div>
  );
}
