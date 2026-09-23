import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { Button } from "../../components/Button";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { RecentAuthenticationPrompt } from "./RecentAuthenticationPrompt";
import { cancelJob, createExport, createPersonalExport, downloadArchive, listJobs } from "./api";
import type { ExportOptions, Job } from "./api";
import type { RecentAuthGrant } from "./recentAuthentication";

export function DataManagement({ personal = false, history = false }: { personal?: boolean; history?: boolean }) {
  const { user, sessionError } = useAuth();
  if (user === undefined) return <p role="status">{sessionError ? "Account connection unavailable. Reload to try again." : "Checking your account..."}</p>;
  if (!user) return <section className="board-page"><h1>Sign in to manage data.</h1><Link to="/login">Sign in</Link></section>;
  if (!personal && user.role !== "ADMINISTRATOR") return <section className="board-page"><h1>Data management is restricted to administrators.</h1><Link to="/">Back to the community</Link></section>;
  return <ActorExports key={`${user.id}:${personal}:${history}`} actorId={user.id} personal={personal} history={history} />;
}
function ActorExports({ actorId, personal, history }: { actorId: string; personal: boolean; history: boolean }) {
  const [blocked, setBlocked] = useState(false);
  if (blocked) return <section className="board-page"><h1>Data management access is no longer available.</h1><p>Sign in with an authorized account to continue.</p><Link to="/login">Sign in</Link></section>;
  return <ExportScreen actorId={actorId} personal={personal} history={history} onDenied={() => setBlocked(true)} />;
}
type Attempt = { key: string; options: ExportOptions };
type Action = { kind: "create"; attempt: Attempt } | { kind: "download"; job: Job };
type DownloadState = { jobId: string; phase: "starting" | "receiving" | "sent" | "error"; received: number; total: number; error?: string };
function bytesLabel(bytes: number) { return bytes < 1024 ? `${bytes} bytes` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1048576).toFixed(1)} MiB`; }
const defaults: ExportOptions = { includeContacts: false, includeModerationHistory: false };
function denied(error: unknown) { return error instanceof ApiError && (error.status === 401 || error.status === 403 && error.code !== "RECENT_AUTH_REQUIRED"); }
function failureMessage(error: unknown) {
  if (error instanceof ApiError && error.code === "RECENT_AUTH_REQUIRED") return "Password confirmation expired or was already used. Confirm your password again.";
  return error instanceof Error ? error.message : "The transfer could not be completed. Try again.";
}
function stateLabel(job: Job, now: number) {
  if (job.errorCode === "JOB_EXPIRED" || job.state === "READY" && Date.parse(job.expiresAt) <= now) return "Archive expired";
  if (job.state === "READY" && !job.artifactAvailable) return "Archive unavailable";
  return ({ QUEUED: "Queued", RUNNING: "Running", READY: "Ready to download", FAILED: "Failed", CANCELLED: "Cancelled" } as Record<string, string>)[job.state] ?? job.state;
}
function ExportScreen({ actorId, personal, history, onDenied }: { actorId: string; personal: boolean; history: boolean; onDenied: () => void }) {
  const client = useQueryClient();
  const queryPrefix = personal ? "personalTransfers" : "companyTransfers";
  const basePath = personal ? "/account/data" : "/admin/data";
  const [selected, setSelected] = useState<string>();
  const [options, setOptions] = useState<ExportOptions>(defaults);
  const [acknowledged, setAcknowledged] = useState(false);
  const [attempt, setAttempt] = useState<Attempt>();
  const [action, setAction] = useState<Action>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [download, setDownload] = useState<DownloadState>();
  const downloadController = useRef<AbortController | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  const life = useRef({ active: true, controller: new AbortController(), urls: new Set<string>() });
  const heading = useRef<HTMLHeadingElement>(null);
  const query = useQuery({ queryKey: [queryPrefix, actorId, history, cursor], queryFn: async ({ signal }) => {
    try { return await listJobs(cursor, signal, personal, history ? 20 : 1); }
    catch (error) { if (!signal.aborted && life.current.active && denied(error)) onDenied(); throw error; }
  }, retry: false, gcTime: 0, refetchInterval: busy || action ? false : 5_000, refetchOnWindowFocus: !busy && !action });
  useEffect(() => {
    const current = { active: true, controller: new AbortController(), urls: new Set<string>() };
    life.current = current;
    const timer = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => {
      current.active = false; current.controller.abort();
      downloadController.current?.abort();
      window.clearInterval(timer);
      current.urls.forEach(url => URL.revokeObjectURL(url));
      void client.cancelQueries({ queryKey: [queryPrefix, actorId] });
      client.removeQueries({ queryKey: [queryPrefix, actorId] });
    };
  }, [client, actorId, queryPrefix]);
  function failure(value: unknown) {
    if (denied(value)) onDenied();
    setError(failureMessage(value));
  }
  async function refresh() { await client.invalidateQueries({ queryKey: [queryPrefix, actorId] }); }
  function begin() {
    if (busy || !acknowledged) return;
    const next = attempt ?? { key: crypto.randomUUID(), options: { ...options } };
    setAttempt(next); setAction({ kind: "create", attempt: next }); setError(""); setMessage("");
  }
  async function confirmed(grant: RecentAuthGrant) {
    if (!action || busy) return;
    const current = life.current;
    setBusy(true); setAction(undefined); setError(""); setMessage("");
    if (action.kind === "download") {
      downloadController.current = new AbortController();
      setDownload({ jobId: action.job.id, phase: "starting", received: 0, total: 0 });
    }
    try {
      if (Date.parse(grant.expiresAt) <= Date.now()) throw new ApiError("http", "Confirm your password again.", 403, undefined, undefined, "RECENT_AUTH_REQUIRED");
      if (action.kind === "create") {
        if (personal) await createPersonalExport(grant.token, action.attempt.key, current.controller.signal);
        else await createExport(action.attempt.options, grant.token, action.attempt.key, current.controller.signal);
        if (!current.active) return;
        setAttempt(undefined); setAcknowledged(false); setCursor(null);
        setMessage("Export requested. Its status appears under Latest export below.");
      } else {
        const signal = AbortSignal.any([current.controller.signal, downloadController.current!.signal]);
        const blob = await downloadArchive(action.job, grant.token, signal, (received, total) => {
          if (current.active && !signal.aborted) setDownload({ jobId: action.job.id, phase: "receiving", received, total });
        });
        if (!current.active) return;
        signal.throwIfAborted();
        const url = URL.createObjectURL(blob); current.urls.add(url);
        const link = document.createElement("a"); link.href = url; link.download = `commonbeacon-${action.job.id}.zip`;
        document.body.appendChild(link); link.click(); link.remove();
        window.setTimeout(() => { URL.revokeObjectURL(url); current.urls.delete(url); }, 1_000);
        setDownload({ jobId: action.job.id, phase: "sent", received: blob.size, total: blob.size });
      }
      void refresh();
    } catch (value) {
      if (current.active) {
        if (action.kind === "download") {
          if (denied(value)) onDenied();
          setDownload({ jobId: action.job.id, phase: "error", received: 0, total: 0,
            error: downloadController.current?.signal.aborted ? "Download stopped. No file was sent to your browser. You can try again." : failureMessage(value) });
        } else failure(value);
        void refresh();
      }
    } finally { if (current.active) { setBusy(false); if (action.kind === "create") heading.current?.focus(); } }
  }
  async function cancel(job: Job) {
    if (busy) return;
    const current = life.current;
    setBusy(true); setError(""); setMessage("");
    try {
      await cancelJob(job, crypto.randomUUID(), current.controller.signal);
      if (current.active) { setMessage("Cancellation recorded. Refreshing the current job status."); await refresh(); }
    } catch (value) { if (current.active) { failure(value); void refresh(); } }
    finally { if (current.active) setBusy(false); }
  }
  return <section className="board-page data-management">
    <p className="eyebrow">{personal ? "YOUR ACCOUNT" : "ADMINISTRATION"}</p><h1 ref={heading} tabIndex={-1}>{history ? "Export history" : personal ? "Export my data" : "Data management"}</h1>
    {!personal && !history && <p><Link to="/admin/data/imports">Import company data</Link></p>}
    {history && <p><Link to={basePath}>Back to exports</Link></p>}
    {!history && <>
    <p>{personal ? "Download a private copy of your own account data and contributions." : "Export a private copy of your company community."} Exporting and downloading never delete live data.</p>
    <div className="transfer-warning"><strong>{personal ? "Your personal archive contains private data." : "Every company archive is private."}</strong><p>{personal ? "Includes your email, your own hidden content and unpublished articles. Other people's profiles, content, and private moderation decisions are excluded, even for administrators." : "Hidden questions and replies, drafts, and archived articles are always included."} Store the downloaded file securely and share it only with authorized people.</p></div>
    <section className="transfer-panel" aria-labelledby="export-scope"><h2 id="export-scope">{personal ? "Personal export" : "Company export"}</h2>
      {personal ? <><p>Personal portability archive v1: a ZIP containing your profile, authored questions, replies and articles, and your report submissions.</p><h3>Export preview</h3><ul><li>Your display name, email, creation date and current role (informational only).</li><li>Your authored content in all states, including your replies beneath hidden questions.</li><li>Your report receipts and submitted reasons, without decisions, notes or moderator identities.</li><li>Only minimal board context for your questions. Parent and accepted-reply references may be opaque IDs.</li></ul><p>This is not a company backup or a complete community restoration archive. Personal archives cannot be used for company import. Other users' bodies, profiles, credentials and moderation history are excluded.</p></> : <>
      <p>Native archive v1: a ZIP containing a manifest and JSON Lines files. It includes authors, boards, questions, replies, accepted-answer relationships, and all article states.</p>
      <fieldset disabled={busy || !!attempt || !!action}><legend>Optional private sections</legend>
        <label className="transfer-check"><input type="checkbox" checked={options.includeContacts} onChange={e => setOptions({ ...options, includeContacts: e.target.checked })} />Include account contact details</label>
        <p>Email addresses are sensitive. They are excluded unless you select this option.</p>
        <label className="transfer-check"><input type="checkbox" checked={options.includeModerationHistory} onChange={e => setOptions({ ...options, includeModerationHistory: e.target.checked })} />Include private moderation history</label>
        <p>Includes reports, reporter and moderator attribution, decisions, and private notes. Excluded by default.</p>
      </fieldset>
      <section aria-labelledby="export-preview"><h3 id="export-preview">Export preview</h3><ul>
        <li>All supported community content, including hidden content and drafts.</li>
        <li>Contact details: {options.includeContacts ? "included" : "excluded"}.</li>
        <li>Private moderation history: {options.includeModerationHistory ? "included" : "excluded"}.</li>
      </ul><p>This previews the scope, not a count of records. Counts are finalized in the archive manifest.</p></section>
      <p>Excludes passwords, login credentials, sessions, account privileges, attachments, and deployment settings. This is not a database backup. Imported authors do not automatically gain login access.</p></>}
      <p>Downloads expire 24 hours after publication; each download needs fresh password confirmation. Expiry removes the downloadable copy, not community content. Supported archives are limited to 64 MiB compressed and 40,000 records.</p>
      <label className="transfer-check"><input type="checkbox" disabled={busy || !!attempt || !!action} checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} />I understand this archive includes private content and have reviewed {personal ? "the export scope" : "the selected options"}.</label>
      <div className="article-actions"><Button className="button-primary" disabled={!acknowledged || busy || !!action} onClick={begin}>{attempt ? "Retry export request" : "Confirm and create export"}</Button>
        {attempt && !action && <Button disabled={busy} onClick={() => { setAttempt(undefined); setError(""); }}>{personal ? "Start a new request" : "Review different options"}</Button>}</div>
      {attempt && <p>Retry keeps the same request{personal ? "" : " and options"} to avoid duplicate jobs. Before starting a different request, check your history in case the earlier request succeeded.</p>}
    </section>
    </>}
    {action?.kind === "create" && <div className="transfer-panel"><RecentAuthenticationPrompt actorId={actorId} scope={personal ? "PERSONAL_EXPORT" : "COMPANY_EXPORT"} onConfirmed={grant => void confirmed(grant)} onCancel={() => { setAction(undefined); heading.current?.focus(); }} /></div>}
    {busy && download?.phase !== "starting" && download?.phase !== "receiving" && <p role="status">Processing your transfer request...</p>}
    {error && <p className="form-error" role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    <section aria-labelledby="transfer-history"><div className="section-heading"><h2 id="transfer-history">{history ? "Your export history" : "Latest export"}</h2><Button disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{history ? "Refresh history" : "Refresh status"}</Button></div>
      <p>{history ? "Only your requests are shown, up to 20 per page. Select a request to view its details." : "Your latest request is shown, including running or failed attempts."} Status refreshes every five seconds while this page is open.</p>
      {!history && <p><Link to={`${basePath}/history`}>View export history</Link></p>}
      {query.isPending ? <p role="status">Loading exports...</p> : query.isError ? <p role="alert">Exports unavailable. {failureMessage(query.error)} Use the refresh button to try again.</p> : <>
        {query.data.items.length === 0 ? <p className="empty-state">No export requests on this page.</p> : <ol className="transfer-history">{(history ? query.data.items : query.data.items.slice(0, 1)).map(job => <li className={`transfer-panel${history ? " transfer-history-row" : ""}`} key={job.id}>
          {history && <button className="transfer-history-toggle" aria-expanded={selected === job.id} aria-controls={`details-${job.id}`} disabled={busy || !!action} onClick={() => setSelected(selected === job.id ? undefined : job.id)}><span>{stateLabel(job, now)}</span><time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString()}</time><span>{selected === job.id ? "Hide details" : "View details"}</span></button>}
          {(!history || selected === job.id) && <div id={`details-${job.id}`}>
          <h3>{job.kind === "PERSONAL_EXPORT" ? "Personal export" : job.kind === "COMPANY_EXPORT" ? "Company export" : "Company transfer"} — {stateLabel(job, now)}</h3>
          <p className="transfer-id">Reference: {job.id}</p><p>Requested <time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString()}</time></p>
          <p>{job.processedRecords} records processed at the latest checkpoint. This is not a final archive count.</p>
          {job.state === "READY" && <p>Download expiry: <time dateTime={job.expiresAt}>{new Date(job.expiresAt).toLocaleString()}</time>.</p>}
          {job.errorCode && <p>Reason: {job.errorCode}. Review the export options and limits before requesting another export.</p>}
          {(job.state === "FAILED" || job.state === "CANCELLED" || job.state === "READY" && !job.artifactAvailable) && <p>To try again, <Link to={basePath}>review the export scope and create a new export</Link>. Previous jobs are retained in history.</p>}
          <div className="article-actions">{job.allowedActions.includes("CANCEL") && <Button disabled={busy || !!action} onClick={() => void cancel(job)}>Cancel export</Button>}
          {(job.kind === "COMPANY_EXPORT" || job.kind === "PERSONAL_EXPORT") && job.artifactAvailable && job.allowedActions.includes("DOWNLOAD") && Date.parse(job.expiresAt) > now && <Button disabled={busy || !!action} onClick={() => { setAction({ kind: "download", job }); setDownload(undefined); setError(""); setMessage(""); }}>Download archive</Button>}</div>
          {action?.kind === "download" && action.job.id === job.id && <div className="download-feedback">
            <p role="status">Your archive is ready. Confirm your password below to start downloading.</p>
            <RecentAuthenticationPrompt actorId={actorId} scope="DOWNLOAD" onConfirmed={grant => void confirmed(grant)} onCancel={() => setAction(undefined)} />
          </div>}
          {download?.jobId === job.id && <div className="download-feedback">
            {download.phase === "starting" && <p role="status">Starting download… Waiting for the server.</p>}
            {download.phase === "receiving" && <><p role="status">Downloading: {bytesLabel(download.received)} of {bytesLabel(download.total)} ({Math.floor(download.received / download.total * 100)}%).</p><progress aria-label="Archive download progress" value={download.received} max={download.total} /></>}
            {(download.phase === "starting" || download.phase === "receiving") && <Button onClick={() => downloadController.current?.abort()}>Stop download</Button>}
            {download.phase === "sent" && <p role="status">Archive sent to your browser downloads ({bytesLabel(download.total)}). Check your browser’s Downloads list for <strong>commonbeacon-{job.id}.zip</strong>. Your browser may ask where to save it. Keep it private; your community data has not been deleted.</p>}
            {download.phase === "error" && <p className="form-error" role="alert">{download.error}</p>}
          </div>}
          </div>}
        </li>)}</ol>}
        {history && <div className="article-actions"><Button disabled={!cursor || busy || !!action} onClick={() => { setSelected(undefined); setCursor(null); }}>Newest requests</Button><Button disabled={!query.data.nextCursor || busy || !!action} onClick={() => { setSelected(undefined); setCursor(query.data.nextCursor); }}>Older requests</Button></div>}
      </>}
    </section>
  </section>;
}
