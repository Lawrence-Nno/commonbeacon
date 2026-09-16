import { ApiError, getJson, postJson } from "../../lib/http";

export type Board = {
  id: string;
  slug: string;
  name: string;
  description: string;
  archived: boolean;
  createdAt: string;
  version: number;
};
export type BoardInput = Pick<Board, "slug" | "name" | "description">;
export function readBoard(value: unknown): Board {
  if (
    typeof value === "object" &&
    value !== null &&
    "id" in value &&
    typeof value.id === "string" &&
    "slug" in value &&
    typeof value.slug === "string" &&
    "name" in value &&
    typeof value.name === "string" &&
    "description" in value &&
    typeof value.description === "string" &&
    "archived" in value &&
    typeof value.archived === "boolean" &&
    "createdAt" in value &&
    typeof value.createdAt === "string" &&
    "version" in value &&
    typeof value.version === "number" &&
    Number.isSafeInteger(value.version) &&
    value.version >= 0
  ) {
    return {
      id: value.id,
      slug: value.slug,
      name: value.name,
      description: value.description,
      archived: value.archived,
      createdAt: value.createdAt,
      version: value.version,
    };
  }
  throw new ApiError("invalid-response", "The board response was unexpected.");
}
function readBoards(value: unknown): Board[] {
  if (!Array.isArray(value))
    throw new ApiError("invalid-response", "The board list was unexpected.");
  return value.map(readBoard);
}
export const listBoards = (signal?: AbortSignal) =>
  getJson("/api/v1/boards", readBoards, signal);
export const getBoard = (id: string, signal?: AbortSignal) =>
  getJson("/api/v1/boards/" + encodeURIComponent(id), readBoard, signal);
export const createBoard = (input: BoardInput) =>
  postJson("/api/v1/boards", input, readBoard);
export const updateBoard = (
  board: Board,
  input: BoardInput & { archived: boolean },
) =>
  postJson(
    "/api/v1/boards/" + encodeURIComponent(board.id),
    { ...input, expectedVersion: board.version },
    readBoard,
    "PATCH",
  );
