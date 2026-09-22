import { useEffect, useId, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Button } from "../../components/Button";
import { confirmTransferPassword } from "./recentAuthentication";
import type { RecentAuthGrant, RecentAuthScope } from "./recentAuthentication";

type Props = { actorId: string; scope: RecentAuthScope; onConfirmed: (grant: RecentAuthGrant) => void; onCancel: () => void };
/** A transient prompt for transfer screens; never persists passwords or grants. */
export function RecentAuthenticationPrompt(props: Props) {
  return <Prompt key={`${props.actorId}:${props.scope}`} {...props} />;
}
function Prompt({ scope, onConfirmed, onCancel }: Props) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const generation = useRef({ value: 0 });
  useEffect(() => {
    input.current?.focus();
    const attempts = generation.current;
    const expired = () => { attempts.value++; setPassword(""); setBusy(false); setError("Please sign in again."); };
    window.addEventListener("commonbeacon:session-expired", expired);
    return () => { attempts.value++; window.removeEventListener("commonbeacon:session-expired", expired); };
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return;
    const attempt = ++generation.current.value;
    const supplied = password;
    setPassword(""); setBusy(true); setError("");
    try {
      const grant = await confirmTransferPassword(supplied, scope);
      if (generation.current.value === attempt) onConfirmed(grant);
    } catch (failure) {
      if (generation.current.value === attempt) setError(failure instanceof Error ? failure.message : "Password confirmation failed.");
    } finally { if (generation.current.value === attempt) setBusy(false); }
  }
  function cancel() { generation.current.value++; setPassword(""); setBusy(false); onCancel(); }
  return <section aria-labelledby={`${id}-title`}>
    <h2 id={`${id}-title`}>Confirm your password</h2>
    <p>Confirm your identity before continuing with this data transfer.</p>
    <form onSubmit={submit}>
      <label htmlFor={id}>Current password</label>
      <input ref={input} id={id} type="password" autoComplete="current-password" required maxLength={128}
        value={password} onChange={event => setPassword(event.target.value)} disabled={busy} />
      {error && <p role="alert">{error}</p>}
      <Button type="submit" disabled={busy}>{busy ? "Checking..." : "Confirm password"}</Button>
      <Button type="button" onClick={cancel}>Cancel</Button>
    </form>
  </section>;
}
