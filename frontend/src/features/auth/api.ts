import { ApiError, getJson, postJson } from "../../lib/http";
export type User = {
  id: string;
  displayName: string;
  role: "MEMBER" | "MODERATOR" | "ADMINISTRATOR";
  // Additive during rollout; older account responses remain readable.
  accountState?: "ACTIVE" | "PENDING_VERIFICATION" | "SUSPENDED" | "IMPORTED_INACTIVE" | "ERASED";
  emailVerified?: boolean;
  capabilities?: { contribute: boolean; moderate: boolean; administer: boolean; personalData: boolean; eraseAccount: boolean };
};
export function canContribute(user: User | null | undefined) { return !!user && (user.capabilities?.contribute ?? (user.accountState === undefined)); }
export function canAdminister(user: User | null | undefined) { return !!user && (user.capabilities?.administer ?? (canContribute(user) && user.role === "ADMINISTRATOR")); }
export function canModerate(user: User | null | undefined) { return !!user && (user.capabilities?.moderate ?? (canContribute(user) && user.role !== "MEMBER")); }
export function readUser(value: unknown): User {
  if (
    typeof value === "object" &&
    value !== null &&
    "id" in value &&
    typeof value.id === "string" &&
    "displayName" in value &&
    typeof value.displayName === "string" &&
    "role" in value &&
    (value.role === "MEMBER" ||
      value.role === "MODERATOR" ||
      value.role === "ADMINISTRATOR")
  ) {
    const user: User = { id: value.id, displayName: value.displayName, role: value.role };
    if ("accountState" in value) {
      const state = value.accountState;
      if (state !== "ACTIVE" && state !== "PENDING_VERIFICATION" && state !== "SUSPENDED" && state !== "IMPORTED_INACTIVE" && state !== "ERASED") {
        throw new ApiError("invalid-response", "The account response was unexpected.");
      }
      user.accountState = state;
    }
    if ("emailVerified" in value) {
      if (typeof value.emailVerified !== "boolean") {
        throw new ApiError("invalid-response", "The account response was unexpected.");
      }
      user.emailVerified = value.emailVerified;
    }
    if ("capabilities" in value) {
      const c = value.capabilities;
      if (typeof c !== "object" || c === null || !("contribute" in c) || typeof c.contribute !== "boolean" || !("moderate" in c) || typeof c.moderate !== "boolean" || !("administer" in c) || typeof c.administer !== "boolean" || !("personalData" in c) || typeof c.personalData !== "boolean" || !("eraseAccount" in c) || typeof c.eraseAccount !== "boolean") {
        throw new ApiError("invalid-response", "The account response was unexpected.");
      }
      user.capabilities = { contribute: c.contribute, moderate: c.moderate, administer: c.administer, personalData: c.personalData, eraseAccount: c.eraseAccount };
    }
    return user;
  }
  throw new ApiError(
    "invalid-response",
    "The account response was unexpected.",
  );
}
export async function currentUser(signal?: AbortSignal) {
  try {
    return await getJson("/api/v1/auth/me", readUser, signal);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}
export function register(values: {
  email: string;
  displayName: string;
  password: string;
}) {
  return postJson("/api/v1/auth/register", values, readUser);
}
export function login(email: string, password: string) {
  return postJson(
    "/api/v1/auth/login",
    new URLSearchParams({ email, password }),
    readUser,
  );
}
export function logout() {
  return postJson("/api/v1/auth/logout", null, () => undefined);
}
