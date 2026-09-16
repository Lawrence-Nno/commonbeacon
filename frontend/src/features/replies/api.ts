import { ApiError, getJson, postJson } from "../../lib/http";
import type { Author } from "../questions/api";

export type Reply = {
  id: string;
  questionId: string;
  body: string;
  author: Author;
  createdAt: string;
  updatedAt: string;
  version: number;
};
export type ReplyPage = {
  items: Reply[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
const invalid = (): never => {
  throw new ApiError("invalid-response", "The reply response was unexpected.");
};
function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : invalid();
}
function text(value: unknown): string {
  return typeof value === "string" ? value : invalid();
}
function count(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : invalid();
}
export function readReply(value: unknown): Reply {
  const reply = record(value),
    author = record(reply.author);
  return {
    id: text(reply.id),
    questionId: text(reply.questionId),
    body: text(reply.body),
    author: { id: text(author.id), displayName: text(author.displayName) },
    createdAt: text(reply.createdAt),
    updatedAt: text(reply.updatedAt),
    version: count(reply.version),
  };
}
export function readReplyPage(value: unknown): ReplyPage {
  const page = record(value);
  if (!Array.isArray(page.items)) return invalid();
  const size = count(page.size);
  if (size < 1 || size > 100) return invalid();
  return {
    items: page.items.map(readReply),
    page: count(page.page),
    size,
    totalElements: count(page.totalElements),
    totalPages: count(page.totalPages),
  };
}
export const listReplies = (
  questionId: string,
  page: number,
  signal?: AbortSignal,
) =>
  getJson(
    "/api/v1/questions/" +
      encodeURIComponent(questionId) +
      "/replies?page=" +
      page +
      "&size=20",
    readReplyPage,
    signal,
  );
export const getReply = (id: string) =>
  getJson("/api/v1/replies/" + encodeURIComponent(id), readReply);
export const createReply = (questionId: string, body: string) =>
  postJson(
    "/api/v1/questions/" + encodeURIComponent(questionId) + "/replies",
    { body },
    readReply,
  );
export const updateReply = (reply: Reply, body: string) =>
  postJson(
    "/api/v1/replies/" + encodeURIComponent(reply.id),
    { body, expectedVersion: reply.version },
    readReply,
    "PATCH",
  );
