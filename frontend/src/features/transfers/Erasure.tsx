import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { Button } from "../../components/Button";
import { ApiError, postJson } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { RecentAuthenticationPrompt } from "./RecentAuthenticationPrompt";
import type { RecentAuthGrant } from "./recentAuthentication";
import "./imports.css";

type Preview = { id: string; digest: string; phrase: string; instanceId: string; expiresAt: string; backupRetentionDays: number; ownQuestions: number; ownReplies: number; counts: Record<string, number> };
type Receipt = { id: string; token: string };
const validId = (s: unknown): s is string => typeof s === "string" && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(s);
const token = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
function readPreview(value: unknown): Preview {
  if (!value || typeof value !== "object") throw new Error("Invalid erasure preview.");
  const p = value as Preview;
  if (!validId(p.id) || !validId(p.instanceId) || typeof p.digest !== "string" || !/^[a-f0-9]{64}$/.test(p.digest)
    || typeof p.phrase !== "string" || p.phrase.length > 100 || !Number.isFinite(Date.parse(p.expiresAt))
    || !Number.isInteger(p.backupRetentionDays) || p.backupRetentionDays < 1 || p.backupRetentionDays > 365
    || !Number.isSafeInteger(p.ownQuestions) || !Number.isSafeInteger(p.ownReplies) || !p.counts || typeof p.counts !== "object"
    || Object.keys(p.counts).length > 12 || Object.values(p.counts).some(n => !Number.isSafeInteger(n) || n < 0)) throw new Error("Invalid erasure preview.");
  return p;
}
const message = (e: unknown) => e instanceof Error ? e.message : "The request could not be confirmed.";
const countLabels: Record<string, string> = { app_user: "Accounts (your administrator account remains)", board: "Boards", question: "Questions", reply: "Replies", knowledge_article: "Articles", content_report: "Reports", moderation_action: "Moderation actions", transfer_artifact: "Registered transfer files", own_reports: "Your reports", own_moderation_notes: "Your moderation notes", own_resolution_notes: "Your report resolution notes", own_articles: "Your retained articles" };
export function Erasure({ company = false }: { company?: boolean }) {
  const { user } = useAuth();const [receipt, setReceipt] = useState<Receipt>();
  if (receipt) return <ReceiptStatus receipt={receipt} />;
  if (user === undefined) return <p role="status">Checking your account...</p>;
  if (!user) return <section className="board-page"><h1>Sign in to review erasure.</h1><Link to="/login">Sign in</Link><p><Link to="/erasure/receipt">Check a saved erasure receipt</Link></p></section>;
  if (company && user.role !== "ADMINISTRATOR") return <section className="board-page"><h1>Company erasure requires an administrator.</h1></section>;
  return <ErasureForm key={`${user.id}:${company}`} actorId={user.id} company={company} onReceipt={setReceipt} />;
}
function ErasureForm({ actorId, company, onReceipt }: { actorId: string; company: boolean; onReceipt: (r: Receipt) => void }) {
  const { user, setSession } = useAuth();const [preview, setPreview] = useState<Preview>();const [phrase, setPhrase] = useState("");
  const [acks, setAcks] = useState<Record<string, boolean>>({});const [prompt, setPrompt] = useState(false);const [busy, setBusy] = useState(false);const [error, setError] = useState("");
  const life = useRef({ active: true, controller: new AbortController() });
  useEffect(() => { const state = life.current;state.active = true;state.controller = new AbortController();return () => { state.active = false;state.controller.abort(); }; }, []);
  const scope = company ? "COMPANY" : "ACCOUNT";
  const labels: Record<string, string> = {
    IRREVERSIBLE: "I understand confirmed erasure cannot be cancelled or undone in this installation.",
    RETAINED_DATA: company ? "I understand my administrator account, installation settings and minimal erasure ledger remain." : "I understand my contributions remain attributed to Deleted member, including text that may still identify me. Minimal history and restore-suppression identifiers remain.",
    ALL_TRANSFER_FILES: "I understand this temporarily pauses the whole community and removes all registered transfer files, staged imports and detailed reviews, including other users' exports.",
    BACKUP_RETENTION: `I understand operators must expire older backups within ${preview?.backupRetentionDays ?? 30} days and replay the erasure ledger before restoring service. Downloaded copies cannot be recalled.`,
  };
  async function inspect() {
    setBusy(true);setError("");setPreview(undefined);setPhrase("");setAcks({});
    try {const p = await postJson("/api/v1/erasure/previews", { scope }, readPreview, "POST", { signal: life.current.controller.signal });if (life.current.active) setPreview(p);}
    catch (e) { if (life.current.active) setError(message(e)); } finally { if (life.current.active) setBusy(false); }
  }
  async function confirm(grant: RecentAuthGrant) {
    if (!preview || busy) return;setPrompt(false);setBusy(true);setError("");
    const receipt = { id: preview.id, token: token() };
    try {
      if (Date.parse(preview.expiresAt) <= Date.now()) throw new Error("Preview expired. Review a fresh impact preview.");
      await postJson(`/api/v1/erasure/${preview.id}/confirm`, { digest: preview.digest, phrase, acknowledgements: Object.keys(labels).filter(k => acks[k]), receiptToken: receipt.token, recentAuthGrant: grant.token }, () => null, "POST", { signal: life.current.controller.signal });
      if (!life.current.active) return;onReceipt(receipt);await setSession(company ? user ?? null : null);
    } catch (e) {
      if (!life.current.active) return;
      if (e instanceof ApiError && e.status && e.status < 500 || e instanceof Error && e.message.startsWith("Preview expired")) {
        setError(message(e));setPreview(undefined);setAcks({});setPhrase("");
      } else { onReceipt(receipt); }
    } finally { if (life.current.active) setBusy(false); }
  }
  return <section className="board-page import-screen"><h1>{company ? "Erase company data" : "Delete my account"}</h1>
    <p><Link to={company ? "/admin/data" : "/account/data"}>Export data first, if you want a copy</Link>. Exporting is optional and never authorizes deletion.</p>
    <div className="transfer-warning"><p>{company ? "This erases community content, all other accounts, imported provenance and transfer data from this installation. Your administrator account remains so you can manage the empty installation. An operator must first enable company erasure." : "Your email, password and display name will be removed, and your sessions will lose access. Your questions, replies and articles remain under Deleted member. Your structured report text and moderation notes are removed. Edit identifying text in your contributions before proceeding if needed."}</p>
      <p>Erasure temporarily pauses reads, writes and transfers for the whole community. Confirmed jobs resume after interruption. Backups and copies outside this installation require operator handling.</p></div>
    {error && <p role="alert">{error}</p>}
    <Button disabled={busy || prompt} onClick={() => void inspect()}>Review erasure impact</Button>
    {preview && <section className="transfer-panel"><h2>Review before erasure</h2><p className="import-reference">Instance: {preview.instanceId}</p>
      <p>{preview.ownQuestions} questions and {preview.ownReplies} replies authored by you.</p>
      <ul>{Object.entries(preview.counts).map(([name, count]) => <li key={name}>{countLabels[name] ?? name.replaceAll("_", " ")}: {count.toLocaleString()}</li>)}</ul>
      <p>This preview expires at {new Date(preview.expiresAt).toLocaleTimeString()}. Changes require a fresh preview.</p>
      <fieldset disabled={busy || prompt}><legend>Required acknowledgements</legend>{Object.entries(labels).map(([key, label]) => <label className="transfer-check" key={key}><input type="checkbox" checked={!!acks[key]} onChange={e => setAcks(old => ({ ...old, [key]: e.target.checked }))} />{label}</label>)}</fieldset>
      <label htmlFor="erasure-phrase">Type exactly: <span className="import-reference">{preview.phrase}</span></label><input className="erasure-input" id="erasure-phrase" value={phrase} onChange={e => setPhrase(e.target.value)} autoComplete="off" disabled={busy || prompt} />
      <Button disabled={busy || prompt || phrase !== preview.phrase || !Object.keys(labels).every(k => acks[k])} onClick={() => setPrompt(true)}>Confirm password and erase</Button>
    </section>}
    {prompt && <RecentAuthenticationPrompt actorId={actorId} scope={company ? "COMPANY_ERASURE" : "ACCOUNT_ERASURE"} onConfirmed={g => void confirm(g)} onCancel={() => setPrompt(false)} />}
    <p><Link to="/erasure/receipt">Check a saved erasure receipt</Link></p>
  </section>;
}
function ReceiptStatus({ receipt }: { receipt: Receipt }) {
  const [status, setStatus] = useState<{ state: string; processed: number; retrying: boolean }>();const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();let timer: number;
    async function poll() {
      try {
        const response = await fetch(`/api/v1/erasure/receipts/${receipt.id}`, { headers: { "X-Erasure-Receipt": receipt.token }, cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
        if (!response.ok) throw new Error("Outcome not confirmed. Keep this receipt and check again; do not submit erasure again automatically.");
        const value = await response.json();if (value.id !== receipt.id || !["RUNNING", "COMPLETED"].includes(value.state) || !Number.isSafeInteger(value.processed) || typeof value.retrying !== "boolean") throw new Error("Invalid receipt response.");
        if (!controller.signal.aborted) {setStatus(value);setError("");if (value.state === "COMPLETED") return;}
      } catch (e) { if (!controller.signal.aborted) setError(message(e)); }
      if (!controller.signal.aborted) timer = window.setTimeout(() => void poll(), 3000);
    }
    void poll();return () => { controller.abort();window.clearTimeout(timer); };
  }, [receipt]);
  function save() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(receipt)], { type: "application/json" }));const a = document.createElement("a");a.href = url;a.download = "commonbeacon-erasure-receipt.json";a.click();window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section className="board-page"><h1>Erasure progress</h1><p className="import-reference">Reference: {receipt.id}</p>
    {error && <p role="alert">{error}</p>}<p role="status">{status?.state === "COMPLETED" ? "Erasure completed for the disclosed scope." : status?.retrying ? "Erasure is paused for retry. Maintenance remains active; an operator may need to restore storage or database availability." : "Checking erasure progress..."}</p>
    <p>{status?.processed ?? 0} work units completed. This is progress, not a count of people or proof that external backups were removed.</p>
    <Button onClick={save}>Save private status receipt</Button><p>Save before leaving. The receipt is kept only in this page's memory and expires after 30 days. It can view status but cannot authorize another erasure.</p><Link to="/">Return to community</Link>
  </section>;
}
export function ErasureReceipt() {
  const [id, setId] = useState("");const [secret, setSecret] = useState("");const [receipt, setReceipt] = useState<Receipt>();
  if (receipt) return <ReceiptStatus receipt={receipt} />;
  return <section className="board-page"><h1>Check an erasure receipt</h1><label htmlFor="receipt-id">Receipt ID</label><input className="erasure-input" id="receipt-id" value={id} onChange={e => setId(e.target.value)} /><label htmlFor="receipt-token">Receipt token</label><input className="erasure-input" id="receipt-token" type="password" autoComplete="off" value={secret} onChange={e => setSecret(e.target.value)} /><Button disabled={!validId(id) || !/^[A-Za-z0-9_-]{43}$/.test(secret)} onClick={() => setReceipt({ id, token: secret })}>Check erasure status</Button></section>;
}
