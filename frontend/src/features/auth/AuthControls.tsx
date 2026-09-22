import { useState } from "react";
import { Link } from "react-router";
import { Button } from "../../components/Button";
import { logout } from "./api";
import { useAuth } from "./AuthProvider";

export function AuthControls() {
  const { user, sessionError, setSession } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function signOut() {
    setBusy(true);
    setError("");
    try {
      await logout();
      await setSession(null);
    } catch {
      setError("Could not sign out. Please try again.");
    } finally {
      setBusy(false);
    }
  }
  if (user === undefined && !sessionError)
    return <span className="auth-caption">Checking account…</span>;
  return (
    <div className="auth-controls">
      {user ? (
        <>
          <span className="member-name">{user.displayName}</span>
          <Link className="text-link" to="/account/data">Export my data</Link>
          <Button
            className="button-secondary"
            disabled={busy}
            onClick={() => void signOut()}
          >
            {busy ? "Signing out…" : "Sign out"}
          </Button>
        </>
      ) : (
        <>
          <Link className="text-link" to="/login">
            Sign in
          </Link>
          <Link className="button button-primary" to="/register">
            Join the community
          </Link>
        </>
      )}
      {error && <span role="alert">{error}</span>}
      {sessionError && (
        <span className="auth-caption">Account connection unavailable</span>
      )}
    </div>
  );
}
