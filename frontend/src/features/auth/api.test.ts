import { afterEach, expect, it, vi } from "vitest";
import { login, logout } from "./api";

afterEach(() => vi.unstubAllGlobals());
it("renews CSRF across login/logout, encodes credentials, and accepts an empty logout response", async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ headerName: "X-CSRF-TOKEN", token: "before-login" }),
    )
    .mockResolvedValueOnce(
      Response.json({ id: "a", displayName: "A", role: "MEMBER" }),
    )
    .mockResolvedValueOnce(
      Response.json({ headerName: "X-CSRF-TOKEN", token: "after-login" }),
    )
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);
  await login("a+b@example.test", "password&with=characters");
  await expect(logout()).resolves.toBeUndefined();
  expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
    "/api/v1/auth/csrf",
    "/api/v1/auth/login",
    "/api/v1/auth/csrf",
    "/api/v1/auth/logout",
  ]);
  const loginOptions = fetchMock.mock.calls[1][1];
  expect(loginOptions.headers["X-CSRF-TOKEN"]).toBe("before-login");
  expect(new URLSearchParams(loginOptions.body).get("password")).toBe(
    "password&with=characters",
  );
  expect(new URLSearchParams(loginOptions.body).get("email")).toBe(
    "a+b@example.test",
  );
  expect(fetchMock.mock.calls[3][1].headers["X-CSRF-TOKEN"]).toBe(
    "after-login",
  );
});
it("does not retry a failed mutation automatically", async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ headerName: "X-CSRF-TOKEN", token: "token" }),
    )
    .mockRejectedValueOnce(new TypeError("connection lost"));
  vi.stubGlobal("fetch", fetchMock);
  await expect(login("a@example.test", "password")).rejects.toMatchObject({
    kind: "network",
  });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
