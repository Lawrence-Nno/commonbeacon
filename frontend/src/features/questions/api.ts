import { ApiError, getJson, postJson } from "../../lib/http";
import { readReply } from "../replies/api";
import type { Reply } from "../replies/api";

export type Author = { id: string; displayName: string };
type QuestionFields = {
  id: string;
  title: string;
  author: Author;
  createdAt: string;
  updatedAt: string;
  version: number;
  solved: boolean;
};
export type QuestionSummary = QuestionFields & { boardId: string };
export type Question = QuestionFields & {
  body: string;
  acceptedReply: Reply | null;
  board: { id: string; name: string; archived: boolean };
};
export type QuestionInput = { title: string; body: string };
export type QuestionPage = {
  items: QuestionSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

function invalid(): never {
  throw new ApiError(
    "invalid-response",
    "The question response was unexpected.",
  );
}
function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return invalid();
  return value as Record<string, unknown>;
}
function string(value: unknown): string {
  return typeof value === "string" ? value : invalid();
}
function integer(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : invalid();
}
function fields(value: Record<string, unknown>): QuestionFields {
  const author = record(value.author);
  if (typeof value.solved !== "boolean") return invalid();
  return {
    id: string(value.id),
    title: string(value.title),
    author: { id: string(author.id), displayName: string(author.displayName) },
    createdAt: string(value.createdAt),
    updatedAt: string(value.updatedAt),
    version: integer(value.version),
    solved: value.solved,
  };
}
export function readQuestion(value: unknown): Question {
  const item = record(value);
  const board = record(item.board);
  if (typeof board.archived !== "boolean") return invalid();
  const acceptedReply = item.acceptedReply === null ? null : readReply(item.acceptedReply);
  if (item.solved !== (acceptedReply !== null) || (acceptedReply && acceptedReply.questionId !== item.id)) return invalid();
  return {
    ...fields(item),
    body: string(item.body),
    acceptedReply,
    board: {
      id: string(board.id),
      name: string(board.name),
      archived: board.archived,
    },
  };
}
export function readQuestionPage(value: unknown): QuestionPage {
  const page = record(value);
  if (!Array.isArray(page.items)) return invalid();
  const size = integer(page.size);
  if (size < 1 || size > 100) return invalid();
  return {
    items: page.items.map((value) => {
      const item = record(value);
      return { ...fields(item), boardId: string(item.boardId) };
    }),
    page: integer(page.page),
    size,
    totalElements: integer(page.totalElements),
    totalPages: integer(page.totalPages),
  };
}
export const listQuestions = (
  boardId: string,
  page: number,
  signal?: AbortSignal,
  status = "all",
) =>
  getJson(
    "/api/v1/boards/" +
      encodeURIComponent(boardId) +
      "/questions?page=" +
      page +
      "&size=20" + (status === "all" ? "" : "&status=" + encodeURIComponent(status)),
    readQuestionPage,
    signal,
  );
export const getQuestion = (id: string, signal?: AbortSignal) =>
  getJson("/api/v1/questions/" + encodeURIComponent(id), readQuestion, signal);
export const createQuestion = (boardId: string, input: QuestionInput) =>
  postJson(
    "/api/v1/boards/" + encodeURIComponent(boardId) + "/questions",
    input,
    readQuestion,
  );
export const updateQuestion = (question: Question, input: QuestionInput) =>
  postJson(
    "/api/v1/questions/" + encodeURIComponent(question.id),
    { ...input, expectedVersion: question.version },
    readQuestion,
    "PATCH",
  );

export const selectSolution = (question: Question, replyId: string | null) =>
  postJson(
    "/api/v1/questions/" + encodeURIComponent(question.id) + "/accepted-reply" +
      (replyId === null ? "?expectedVersion=" + question.version : ""),
    replyId === null ? null : { replyId, expectedVersion: question.version },
    readQuestion,
    replyId === null ? "DELETE" : "PUT",
  );
