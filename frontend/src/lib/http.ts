export type ApiErrorKind = "http" | "network" | "timeout" | "invalid-response";

export class ApiError extends Error {
  constructor(
    public readonly kind: ApiErrorKind,
    message: string,
    public readonly status?: number,
    public readonly requestId?: string,
    public readonly fieldErrors?: Record<string, string>,
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
  options: RequestInit = {},
): Promise<T> {
  if (!path.startsWith("/api/"))
    throw new Error("API paths must start with /api/");
  const timeout = AbortSignal.timeout(timeoutMs);
  const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
  try {
    const response = await fetch(path, {
      ...options,
      credentials: "same-origin",
      headers: { Accept: "application/json", ...options.headers },
      signal: requestSignal,
    });
    if (!response.ok) {
      let message =
        response.status === 401
          ? "Please sign in to continue."
          : "The community service is unavailable. Please try again.";
      let fields: Record<string, string> | undefined;
      const isAuth = path.startsWith("/api/v1/auth/");
      if (
        (isAuth ||
          path === "/api/v1/boards" ||
          path === "/api/v1/reports" ||
          path.startsWith("/api/v1/moderation/") ||
          path.startsWith("/api/v1/admin/articles") ||
          path.startsWith("/api/v1/articles") ||
          path.startsWith("/api/v1/boards/") ||
          path.startsWith("/api/v1/questions/") ||
          path.startsWith("/api/v1/replies/")) &&
        response.headers
          .get("content-type")
          ?.includes("application/problem+json")
      ) {
        try {
          const problem: unknown = await response.json();
          if (typeof problem === "object" && problem !== null) {
            if ("detail" in problem && typeof problem.detail === "string")
              message = problem.detail;
            if (
              "fieldErrors" in problem &&
              typeof problem.fieldErrors === "object" &&
              problem.fieldErrors !== null
            ) {
              fields = Object.fromEntries(
                Object.entries(problem.fieldErrors).filter(
                  (entry): entry is [string, string] =>
                    typeof entry[1] === "string",
                ),
              );
            }
          }
        } catch {
          /* The generic message remains valid when no problem body is available. */
        }
      }
      if (response.status === 401 && !isAuth)
        window.dispatchEvent(new Event("commonbeacon:session-expired"));
      throw new ApiError(
        "http",
        message,
        response.status,
        response.headers.get("x-request-id") ?? undefined,
        fields,
      );
    }
    if (response.status === 204) return decode(undefined);
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

type Csrf = { headerName: string; token: string };
function readCsrf(value: unknown): Csrf {
  if (
    typeof value === "object" &&
    value !== null &&
    "headerName" in value &&
    value.headerName === "X-CSRF-TOKEN" &&
    "token" in value &&
    typeof value.token === "string"
  )
    return { headerName: value.headerName, token: value.token };
  throw new ApiError(
    "invalid-response",
    "Could not establish a secure session.",
  );
}
export async function postJson<T>(
  path: string,
  body: unknown,
  decode: (value: unknown) => T,
  method: "POST" | "PATCH" | "PUT" | "DELETE" = "POST",
): Promise<T> {
  // Obtain a fresh session token for every mutation, including after login/logout.
  // Do not retry a mutation automatically: it may already have taken effect.
  const csrf = await getJson("/api/v1/auth/csrf", readCsrf);
  const form = body instanceof URLSearchParams;
  return getJson(path, decode, undefined, 8_000, {
    method,
    headers: {
      "Content-Type": form
        ? "application/x-www-form-urlencoded"
        : "application/json",
      [csrf.headerName]: csrf.token,
    },
    body:
      body === null ? undefined : form ? body.toString() : JSON.stringify(body),
  });
}
