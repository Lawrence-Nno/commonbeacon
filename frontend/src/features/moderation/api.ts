import { ApiError, postJson } from "../../lib/http";

export type ReportTarget =
  | { questionId: string; replyId?: never }
  | { replyId: string; questionId?: never };
export type ReportReceipt = { id: string; status: "OPEN"; createdAt: string };

export function readReportReceipt(value: unknown): ReportReceipt {
  if (
    typeof value !== "object" || value === null ||
    !("id" in value) || typeof value.id !== "string" ||
    !("status" in value) || value.status !== "OPEN" ||
    !("createdAt" in value) || typeof value.createdAt !== "string"
  ) throw new ApiError("invalid-response", "The report response was not valid.");
  return { id: value.id, status: value.status, createdAt: value.createdAt };
}

export const createReport = (target: ReportTarget, reason: string) =>
  postJson("/api/v1/reports", { ...target, reason }, readReportReceipt);
