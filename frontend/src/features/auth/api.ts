import { ApiError, getJson, postJson } from "../../lib/http";
export type User = {
  id: string;
  displayName: string;
  role: "MEMBER" | "MODERATOR" | "ADMINISTRATOR";
};
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
    return { id: value.id, displayName: value.displayName, role: value.role };
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
