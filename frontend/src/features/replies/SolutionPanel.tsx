import type { Question } from "../questions/api";
import type { SolutionState } from "./useSolution";

export function SolutionPanel({
  question,
  canSelect,
  state,
}: {
  question: Question;
  canSelect: boolean;
  state: SolutionState;
}) {
  return (
    <>
      {question.acceptedReply && (
        <section className="accepted-answer" aria-labelledby="accepted-heading">
          <p className="eyebrow">SOLVED</p>
          <h2 id="accepted-heading">Accepted answer</h2>
          <p className="question-meta">
            By {question.acceptedReply.author.displayName}
          </p>
          <div className="reply-body">{question.acceptedReply.body}</div>
          {canSelect && (
            <button
              className="button button-secondary"
              disabled={state.busy || !!state.error}
              onClick={() => void state.select(null)}
            >
              Clear solution
            </button>
          )}
        </section>
      )}
      {state.error && (
        <div className="form-error" role="alert">
          <p>{state.error}</p>
          <button
            className="button button-secondary"
            disabled={state.busy}
            onClick={() => void state.reload()}
          >
            Reload solution status
          </button>
        </div>
      )}
      {state.message && (
        <p className="success-notice" role="status">
          {state.message}
        </p>
      )}
    </>
  );
}
export function SolutionButton({
  question,
  replyId,
  canSelect,
  state,
}: {
  question: Question;
  replyId: string;
  canSelect: boolean;
  state: SolutionState;
}) {
  if (question.acceptedReply?.id === replyId)
    return <p className="subtle-badge">Accepted solution</p>;
  if (!canSelect) return null;
  return (
    <button
      className="button button-secondary"
      disabled={state.busy || !!state.error}
      onClick={() => void state.select(replyId)}
    >
      {question.solved
        ? "Replace solution with this reply"
        : "Accept as solution"}
    </button>
  );
}
