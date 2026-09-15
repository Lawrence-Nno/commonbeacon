import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson } from "./http";
import { readHealth } from "../features/connection/health";

afterEach(() => vi.unstubAllGlobals());

describe("HTTP boundary", () => {
  it("uses same-origin credentials and validates a healthy response", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        Response.json({ status: "UP", groups: ["readiness"] }),
      );
    vi.stubGlobal("fetch", fetchMock);
    await expect(getJson("/api/health", readHealth)).resolves.toEqual({
      status: "UP",
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/health",
      expect.objectContaining({
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      }),
    );
  });

  it("preserves status and request ID without exposing a server error body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("private SQL error", {
          status: 503,
          headers: { "x-request-id": "request-123" },
        }),
      ),
    );
    await expect(getJson("/api/health", readHealth)).rejects.toMatchObject({
      kind: "http",
      status: 503,
      requestId: "request-123",
    });
  });

  it("normalizes network errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed")),
    );
    await expect(getJson("/api/health", readHealth)).rejects.toMatchObject({
      kind: "network",
    });
  });

  it("rejects an HTML fallback instead of treating it as healthy JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("<html>SPA</html>", {
          headers: { "content-type": "text/html" },
        }),
      ),
    );
    await expect(getJson("/api/health", readHealth)).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });

  it("rejects malformed JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("{", {
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    await expect(getJson("/api/health", readHealth)).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });

  it("rejects unexpected health payloads", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(Response.json({ status: "DOWN" })),
    );
    await expect(getJson("/api/health", readHealth)).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });

  it("aborts a stalled request at the timeout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_path, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener(
              "abort",
              () => reject(options.signal?.reason),
              { once: true },
            );
          }),
      ),
    );
    await expect(
      getJson("/api/health", readHealth, undefined, 20),
    ).rejects.toMatchObject({ kind: "timeout" });
  });

  it("preserves caller cancellation", async () => {
    const controller = new AbortController();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_path, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener(
              "abort",
              () => reject(options.signal?.reason),
              { once: true },
            );
          }),
      ),
    );
    const pending = getJson("/api/health", readHealth, controller.signal);
    controller.abort();
    await expect(pending).rejects.not.toBeInstanceOf(ApiError);
  });

  it("does not send requests to arbitrary external origins", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(
      getJson("https://example.test/private", readHealth),
    ).rejects.toThrow("API paths");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
