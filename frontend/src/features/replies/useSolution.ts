import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { getQuestion, selectSolution } from "../questions/api";
import type { Question } from "../questions/api";

export function useSolution(question: Question) {
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function select(replyId: string | null) {
    if (busy || error) return;
    setBusy(true);
    setMessage("");
    try {
      const updated = await selectSolution(question, replyId);
      client.setQueryData(["questions", question.id], updated);
      void client.invalidateQueries({ queryKey: ["moderation"] });
      setMessage(replyId === null ? "Solution cleared." : "Solution selected.");
      await client.invalidateQueries({
        queryKey: ["questions", "board", question.board.id],
      });
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Could not change the solution.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    if (busy) return;
    setBusy(true);
    try {
      const latest = await getQuestion(question.id);
      client.setQueryData(["questions", question.id], latest);
      setError("");
    } catch {
      setError("Could not reload the question. Please try again.");
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, message, select, reload };
}
export type SolutionState = ReturnType<typeof useSolution>;
