export type ApiErrorKind = "http" | "network" | "timeout" | "invalid-response";

export class ApiError extends Error {
  constructor(
    public readonly kind: ApiErrorKind,
    message: string,
    public readonly status?: number,
    public readonly requestId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function getJson<T>(
  path: string,
  decode: (value: unknown) => T,
  signal?: AbortSignal,
  timeoutMs = 8_000,
): Promise<T> {
  if (!path.startsWith("/api/"))
    throw new Error("API paths must start with /api/");
  const timeout = AbortSignal.timeout(timeoutMs);
  const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
  try {
    const response = await fetch(path, {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
      signal: requestSignal,
    });
    if (!response.ok) {
      throw new ApiError(
        "http",
        response.status === 401
          ? "Please sign in to continue."
          : "The community service is unavailable. Please try again.",
        response.status,
        response.headers.get("x-request-id") ?? undefined,
      );
    }
    const contentType = response.headers.get("content-type") ?? "";
    if (
      !contentType.includes("application/json") &&
      !contentType.includes("+json")
    ) {
      throw new ApiError(
        "invalid-response",
        "The service returned an unexpected response.",
      );
    }
    let value: unknown;
    try {
      value = await response.json();
    } catch {
      if (requestSignal.aborted) throw requestSignal.reason;
      throw new ApiError(
        "invalid-response",
        "The service returned an unreadable response.",
      );
    }
    return decode(value);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (signal?.aborted) throw error;
    if (timeout.aborted)
      throw new ApiError(
        "timeout",
        "The connection took too long. Please try again.",
      );
    throw new ApiError(
      "network",
      "We could not reach the community. Check your connection and try again.",
    );
  }
}
