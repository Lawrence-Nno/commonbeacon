import { ApiError, getJson } from "../../lib/http";

export type Health = { status: "UP" };

export function readHealth(value: unknown): Health {
  if (
    typeof value === "object" &&
    value !== null &&
    "status" in value &&
    value.status === "UP"
  ) {
    return { status: "UP" };
  }
  throw new ApiError(
    "invalid-response",
    "The community service is not ready yet. Please try again.",
  );
}

export function getHealth(signal?: AbortSignal) {
  return getJson("/api/health", readHealth, signal);
}
