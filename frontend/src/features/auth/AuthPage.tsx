import { useState } from "react";
import type { FormEvent } from "react";
import { Link, Navigate, useNavigate } from "react-router";
import { Button } from "../../components/Button";
import { ApiError } from "../../lib/http";
import { login, register } from "./api";
import { useAuth } from "./AuthProvider";

export function AuthPage({ mode }: { mode: "login" | "register" }) {
  const registering = mode === "register";
  const navigate = useNavigate();
  const { user, setSession } = useAuth();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [created, setCreated] = useState(false);
  if (user) return <Navigate to="/" replace />;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setFields({});
    try {
      if (registering) {
        await register({ email, displayName, password });
        setCreated(true);
      } else {
        const next = await login(email, password);
        await setSession(next);
        navigate("/", { replace: true });
      }
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Something went wrong. Please try again.",
      );
      if (failure instanceof ApiError) setFields(failure.fieldErrors ?? {});
    } finally {
      setPassword("");
      setBusy(false);
    }
  }

  return (
    <section className="auth-page">
      <div>
        <p className="eyebrow">
          {registering ? "FIND YOUR PLACE" : "WELCOME BACK"}
        </p>
        <h1>
          {registering
            ? "A brighter community starts with you."
            : "Good to see you again."}
        </h1>
        <p className="hero-description">
          {registering
            ? "Create your member account. Bring your curiosity; your experience belongs here."
            : "Sign in to your CommonBeacon account."}
        </p>
      </div>
      {created ? (
        <div className="auth-form">
          <h2>You're part of CommonBeacon.</h2>
          <p role="status">
            Your account has been created. Sign in to continue.
          </p>
          <Link className="button button-primary" to="/login">
            Continue to sign in
          </Link>
        </div>
      ) : (
        <form className="auth-form" onSubmit={(event) => void submit(event)}>
          <h2>{registering ? "Create an account" : "Sign in"}</h2>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <label htmlFor="email">Email address</label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            required
            maxLength={254}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={!!fields.email}
            aria-describedby={fields.email ? "email-error" : undefined}
          />
          {fields.email && (
            <p id="email-error" className="field-error">
              {fields.email}
            </p>
          )}
          {registering && (
            <>
              <label htmlFor="displayName">Display name</label>
              <input
                id="displayName"
                name="displayName"
                autoComplete="nickname"
                required
                maxLength={80}
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                aria-invalid={!!fields.displayName}
                aria-describedby={fields.displayName ? "name-error" : undefined}
              />
              {fields.displayName && (
                <p id="name-error" className="field-error">
                  {fields.displayName}
                </p>
              )}
            </>
          )}
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            required
            minLength={registering ? 12 : undefined}
            maxLength={128}
            autoComplete={registering ? "new-password" : "current-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={!!fields.password}
            aria-describedby="password-help"
          />
          <p
            id="password-help"
            className={fields.password ? "field-error" : "auth-caption"}
          >
            {fields.password ??
              (registering
                ? "Use 12–128 characters. A long, unique passphrase works well."
                : "Use the password you chose when you joined.")}
          </p>
          <Button className="button-primary" type="submit" disabled={busy}>
            {busy ? "Please wait…" : registering ? "Create account" : "Sign in"}
          </Button>
          <p className="auth-switch">
            {registering ? "Already a member? " : "New to CommonBeacon? "}
            <Link to={registering ? "/login" : "/register"}>
              {registering ? "Sign in" : "Create an account"}
            </Link>
          </p>
        </form>
      )}
    </section>
  );
}
