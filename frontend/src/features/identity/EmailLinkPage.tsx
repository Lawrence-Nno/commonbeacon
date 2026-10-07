import { useEffect, useState, type FormEvent } from "react";
import { takeEmailLink, type EmailLink } from "./emailLink";
import "./emailLink.css";

type Confirmation = { token: string; password?: string };
type Props = { link: EmailLink; confirm?: (value: Confirmation) => Promise<void> };

export function EmailLinkEntry({ initialLink }: { initialLink: EmailLink }) {
  const [link, setLink] = useState(initialLink);
  useEffect(() => {
    function changed() {
      const incoming = takeEmailLink();
      if (incoming) setLink(incoming);
    }
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  return <EmailLinkPage key={link.token ?? "missing"} link={link} />;
}

// Stages 9–12 supply the purpose-specific POST adapter. Merely mounting this
// component never calls it, and the Stage 6 default never mutates an account.
export function EmailLinkPage({ link, confirm }: Props) {
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const reset = link.kind === "password-reset";
  const title = reset ? "Reset your password" : link.kind === "email-change" ? "Confirm your new email" : "Verify your email";
  useEffect(() => { document.title = `${title} · CommonBeacon`; }, [title]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!link.token || busy || done) return;
    if (reset && (password.length < 12 || password.length > 128 || repeat !== password)) {
      setError("Use 12–128 characters and enter the same password in both fields.");
      return;
    }
    setBusy(true); setError("");
    try {
      if (!confirm) throw new Error("unavailable");
      await confirm({ token: link.token, ...(reset ? { password } : {}) });
      setPassword(""); setRepeat(""); setDone(true);
    } catch {
      setError("We could not process this link. Please try again later or request a new link.");
    } finally { setBusy(false); }
  }
  return <main className="email-link-page">
    <a className="brand" href="/">CommonBeacon</a>
    <section className="email-link-card" aria-labelledby="email-link-title">
      <p className="eyebrow">ACCOUNT SECURITY</p>
      <h1 id="email-link-title">{title}</h1>
      {done ? <p role="status">Your request is complete. You can now sign in.</p> : !link.token ?
        <p role="alert">This link is incomplete or no longer available in this window. Open the full link from your email again, or request a new link.</p> : <>
          <p>{reset ? "Choose a new password for your account." : "Confirm below to continue. Opening this page has not changed your account."}</p>
          <form onSubmit={submit}>
            {reset && <>
              <label htmlFor="new-password">New password</label>
              <input id="new-password" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={password} onChange={event => setPassword(event.target.value)} />
              <label htmlFor="repeat-password">Confirm new password</label>
              <input id="repeat-password" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={repeat} onChange={event => setRepeat(event.target.value)} />
            </>}
            {error && <p role="alert">{error}</p>}
            <button className="button button-primary" type="submit" disabled={busy}>{busy ? "Working…" : reset ? "Reset password" : "Confirm email"}</button>
          </form>
        </>}
      <p>If you did not request this, close this page.</p>
      <a href="/login">Go to sign in</a>
    </section>
  </main>;
}
