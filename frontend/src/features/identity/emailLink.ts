export type EmailLinkKind = "verification" | "password-reset" | "email-change";
export type EmailLink = Readonly<{ kind: EmailLinkKind; token?: string }>;

const routes: Record<string, EmailLinkKind> = {
  "/verify-email": "verification",
  "/reset-password": "password-reset",
  "/confirm-email-change": "email-change",
};

// Run before React, routing, authentication, or any request. Secrets live only in
// this document's memory. Reload/back-navigation deliberately cannot recover them.
export function takeEmailLink(): EmailLink | undefined {
  const kind = routes[window.location.pathname];
  if (!kind) return undefined;
  const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(window.location.hash);
  const token = window.location.search ? undefined : match?.[1];
  window.history.replaceState(null, "", window.location.pathname);
  return { kind, token };
}
