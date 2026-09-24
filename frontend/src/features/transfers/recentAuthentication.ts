import { ApiError, postJson } from "../../lib/http";

export type RecentAuthScope = "COMPANY_EXPORT" | "PERSONAL_EXPORT" | "IMPORT_UPLOAD" | "IMPORT_COMMIT" | "DOWNLOAD" | "ACCOUNT_ERASURE" | "COMPANY_ERASURE";
export type RecentAuthGrant = { token: string; expiresAt: string };
export function confirmTransferPassword(password: string, scope: RecentAuthScope) {
  return postJson("/api/v1/account/data/reauthentication", { password, scope }, (value: unknown): RecentAuthGrant => {
    if (typeof value === "object" && value !== null && "token" in value && typeof value.token === "string"
      && /^[A-Za-z0-9_-]{43}$/.test(value.token) && "expiresAt" in value && typeof value.expiresAt === "string"
      && Number.isFinite(Date.parse(value.expiresAt))) return { token: value.token, expiresAt: value.expiresAt };
    throw new ApiError("invalid-response", "Password confirmation returned an unexpected response.");
  });
}
