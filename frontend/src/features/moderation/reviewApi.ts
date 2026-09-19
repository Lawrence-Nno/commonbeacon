import { ApiError, getJson, postJson } from "../../lib/http";

export type ReportStatus = "OPEN" | "RESOLVED";
type Actor = { id: string; displayName: string };
type Visibility = "VISIBLE" | "HIDDEN";
export type ReviewReport = {
  id: string; status: ReportStatus; reason: string; reporter: Actor;
  targetKind: "QUESTION" | "REPLY"; targetId: string; questionId: string;
  createdAt: string; updatedAt: string; version: number;
  resolvedAt: string | null; resolver: Actor | null;
  resolutionDecision: string | null; resolutionNote: string | null;
};
export type ReportPage = { items: ReviewReport[]; page: number; size: number; totalElements: number; totalPages: number };
export type ReportDetail = {
  report: ReviewReport;
  context: {
    board: { id: string; name: string; archived: boolean };
    question: { id: string; title: string; body: string; author: Actor; visibility: Visibility; version: number; acceptedReplyId: string | null };
    reply: { id: string; body: string; author: Actor; visibility: Visibility; version: number } | null;
    targetKind: "QUESTION" | "REPLY"; targetId: string; effectivePublicVisibility: boolean;
  };
  availableDecisions: string[];
};
const invalid = (): never => { throw new ApiError("invalid-response", "The report service returned an invalid response."); };
function object(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
function text(value: unknown): string { return typeof value === "string" ? value : invalid(); }
function nullable(value: unknown): string | null { return value === null ? null : text(value); }
function count(value: unknown): number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : invalid(); }
function bool(value: unknown): boolean { return typeof value === "boolean" ? value : invalid(); }
function actor(value: unknown): Actor { const v = object(value); return { id: text(v.id), displayName: text(v.displayName) }; }
function visibility(value: unknown): Visibility { return value === "VISIBLE" || value === "HIDDEN" ? value : invalid(); }
function kind(value: unknown): "QUESTION" | "REPLY" { return value === "QUESTION" || value === "REPLY" ? value : invalid(); }
function report(value: unknown): ReviewReport {
  const v = object(value);
  return { id: text(v.id), status: v.status === "OPEN" || v.status === "RESOLVED" ? v.status : invalid(),
    reason: text(v.reason), reporter: actor(v.reporter), targetKind: kind(v.targetKind), targetId: text(v.targetId),
    questionId: text(v.questionId), createdAt: text(v.createdAt), updatedAt: text(v.updatedAt), version: count(v.version),
    resolvedAt: nullable(v.resolvedAt), resolver: v.resolver === null ? null : actor(v.resolver),
    resolutionDecision: nullable(v.resolutionDecision), resolutionNote: nullable(v.resolutionNote) };
}
export function readReportPage(value: unknown): ReportPage {
  const v = object(value); const size = count(v.size);
  if (!Array.isArray(v.items) || size < 1 || size > 100) return invalid();
  return { items: v.items.map(report), page: count(v.page), size, totalElements: count(v.totalElements), totalPages: count(v.totalPages) };
}
export function readReportDetail(value: unknown): ReportDetail {
  const v = object(value), c = object(v.context), q = object(c.question), b = object(c.board);
  const r = c.reply === null ? null : object(c.reply);
  if (!Array.isArray(v.availableDecisions) || v.availableDecisions.some((d) => !["DISMISS", "HIDE", "ACKNOWLEDGE_HIDDEN"].includes(String(d)))) return invalid();
  return { report: report(v.report), context: {
    board: { id: text(b.id), name: text(b.name), archived: bool(b.archived) },
    question: { id: text(q.id), title: text(q.title), body: text(q.body), author: actor(q.author),
      visibility: visibility(q.visibility), version: count(q.version), acceptedReplyId: nullable(q.acceptedReplyId) },
    reply: r === null ? null : { id: text(r.id), body: text(r.body), author: actor(r.author), visibility: visibility(r.visibility), version: count(r.version) },
    targetKind: kind(c.targetKind), targetId: text(c.targetId), effectivePublicVisibility: bool(c.effectivePublicVisibility),
  }, availableDecisions: v.availableDecisions.map(text) };
}
export const listReports = (status: ReportStatus, page: number, signal?: AbortSignal) =>
  getJson(`/api/v1/moderation/reports?status=${status}&page=${page}&size=20`, readReportPage, signal);
export const getReport = (id: string, signal?: AbortSignal) =>
  getJson("/api/v1/moderation/reports/" + encodeURIComponent(id), readReportDetail, signal);

export function resolveReport(data: ReportDetail, decision: string, resolutionNote: string) {
  return postJson("/api/v1/moderation/reports/" + encodeURIComponent(data.report.id) + "/resolve", {
    decision, resolutionNote: resolutionNote.trim(), expectedVersion: data.report.version,
    expectedTargetVersion: (data.context.reply ?? data.context.question).version,
    ...(data.context.reply ? { expectedQuestionVersion: data.context.question.version } : {}),
  }, readReportDetail);
}
