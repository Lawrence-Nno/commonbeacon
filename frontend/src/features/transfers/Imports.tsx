import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import { Button } from "../../components/Button";
import { ApiError } from "../../lib/http";
import { useAuth } from "../auth/AuthProvider";
import { RecentAuthenticationPrompt } from "./RecentAuthenticationPrompt";
import { cancelJob } from "./api";
import type { Job } from "./api";
import type { RecentAuthGrant } from "./recentAuthentication";
import { activateImport, createImport, dryRun, entities, importHistory, importStatus, inspectImport, reconcileImport, reviewImport, uploadImport } from "./importApi";
import type { Mapping, Reconciliation, Review } from "./importApi";
import "./imports.css";

const base = "/admin/data/imports";
const terminal = (j: Job) => ["COMPLETED", "FAILED", "CANCELLED"].includes(j.state);
const denied = (e: unknown) => e instanceof ApiError && (e.status === 401 || e.status === 403 && e.code !== "RECENT_AUTH_REQUIRED");
const message = (e: unknown) => e instanceof Error ? e.message : "Import unavailable. Check status before continuing.";
const warningText: Record<string, string> = {
  DISCOURSE_PLAIN_TEXT: "Discourse markup is preserved as literal text; formatting, mentions and embeds are not rendered.",
  DISCOURSE_FLATTENED_THREADS: "Subcategories become separate boards and nested replies become flat replies.",
  DISCOURSE_ATTACHMENTS_EXCLUDED: "Attachment files are not copied. Source references remain text and may become unavailable.",
  DISCOURSE_FEATURES_EXCLUDED: "Accepted answers, tags, votes, polls, revisions, closed/archive state and plugin data are excluded.",
  DISCOURSE_PUBLIC_CATEGORIES_ONLY: "Only selected public categories are included; private messages, deleted content and category description topics are excluded.",
  IMPORTED_AUTHORS_INACTIVE: "Imported authors remain inactive and cannot sign in.",
  IMPORTED_HISTORY_PROVENANCE: "Moderation history is imported source history; historical roles grant no local permissions.",
  IDENTITIES_REMAIN_SEPARATE: "Matching names, source IDs or contact emails do not merge accounts or prove ownership.",
  CONTACTS_EXCLUDED: "Contact details were excluded from this archive and cannot be restored.",
  MODERATION_HISTORY_EXCLUDED: "Private moderation history was excluded from this archive and cannot be restored.",
};
const states: Record<string, string> = { UPLOADING: "Waiting for the complete upload", UPLOADED: "Uploaded; waiting for inspection", VALIDATING: "Validating or staging privately", REVIEW_REQUIRED: "Review required", READY_TO_COMMIT: "Ready for your final review", COMMITTING: "Activating atomically; cancellation is closed", COMPLETED: "Import completed", FAILED: "Import failed; no imported records became live", CANCELLED: "Import cancelled; no imported records became live" };
export function Imports({ history = false }: { history?: boolean }) {
  const { user, sessionError } = useAuth(); const { jobId } = useParams();
  if (user === undefined) return <p role="status">{sessionError ? "Account connection unavailable. Reload to continue." : "Checking your account..."}</p>;
  if (!user) return <section className="board-page"><h1>Sign in to import data.</h1><Link to="/login">Sign in</Link></section>;
  if (user.role !== "ADMINISTRATOR") return <section className="board-page"><h1>Import is restricted to administrators.</h1></section>;
  return <PrivateImports key={`${user.id}:${history}:${jobId ?? "new"}`} actorId={user.id} id={jobId} history={history} />;
}
function PrivateImports(props: { actorId: string; id?: string; history: boolean }) {
  const [blocked, setBlocked] = useState(false);
  return blocked ? <section className="board-page"><h1>Import access is no longer available.</h1><Link to="/login">Sign in again</Link></section>
    : <ImportScreen {...props} onDenied={() => setBlocked(true)} />;
}
type Prompt = { kind: "upload" } | { kind: "activate"; job: Job; review: Review; key: string };
function ImportScreen({ actorId, id, history, onDenied }: { actorId: string; id?: string; history: boolean; onDenied: () => void }) {
  const client = useQueryClient(), navigate = useNavigate();
  const [created, setCreated] = useState<Job>(); const selected = id ?? created?.id;
  const [provider, setProvider] = useState<"NATIVE" | "DISCOURSE">("NATIVE");
  const [file, setFile] = useState<File>(); const [fileVersion, setFileVersion] = useState(0);
  const [busy, setBusy] = useState(false), [uploading, setUploading] = useState(false), [sent, setSent] = useState(0);
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const [prompt, setPrompt] = useState<Prompt>(); const [uncertain, setUncertain] = useState(false);
  const [acks, setAcks] = useState<Record<string, boolean>>({}); const [ackDigest, setAckDigest] = useState("");
  const [after, setAfter] = useState<string | null>(null), [mappingAfter, setMappingAfter] = useState<string | null>(null);
  const [issuePage, setIssuePage] = useState(0);
  const [now, setNow] = useState(Date.now);
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(timer); }, []);
  const creationKey = useRef(crypto.randomUUID()); const confirmation = useRef({ digest: "", key: "" });
  const upload = useRef<AbortController | null>(null);
  const life = useRef({ active: true, controller: new AbortController(), urls: new Set<string>() });
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const current = { active: true, controller: new AbortController(), urls: new Set<string>() }; life.current = current;
    return () => { current.active = false; current.controller.abort(); upload.current?.abort(); current.urls.forEach(url => URL.revokeObjectURL(url));
      void client.cancelQueries({ queryKey: ["imports", actorId] }); client.removeQueries({ queryKey: ["imports", actorId] }); };
  }, [actorId, client]);
  function failure(e: unknown) { if (denied(e)) onDenied(); else setError(message(e)); }
  const listing = useQuery({ queryKey: ["imports", actorId, "history", after], enabled: !selected,
    queryFn: async ({ signal }) => { try { return await importHistory(after, signal); } catch (e) { if (!signal.aborted && denied(e)) onDenied(); throw e; } },
    gcTime: 0, retry: false, refetchInterval: busy || prompt ? false : 5000 });
  const detail = useQuery({ queryKey: ["imports", actorId, selected, mappingAfter], enabled: !!selected,
    queryFn: async ({ signal }) => {
      try {
        const job = await importStatus(selected!, signal);
        if (terminal(job)) return { job, result: await reconcileImport(job.id, mappingAfter, signal), review: undefined, inspection: undefined };
        if (["REVIEW_REQUIRED", "READY_TO_COMMIT"].includes(job.state)) {
          try {
            const review = await reviewImport(job.id, signal);
            // Reading a stale review can move READY_TO_COMMIT back to REVIEW_REQUIRED.
            // Use that new version for a user-requested dry run, never for silent confirmation.
            return { job: review.fresh ? job : await importStatus(job.id, signal), review, result: undefined, inspection: undefined };
          }
          catch (e) { if (!(e instanceof ApiError && e.status === 409)) throw e; }
          return { job, inspection: await inspectImport(job.id, signal), review: undefined, result: undefined };
        }
        return { job, review: undefined, inspection: undefined, result: undefined };
      } catch (e) { if (!signal.aborted && denied(e)) onDenied(); throw e; }
    }, gcTime: 0, retry: false, refetchInterval: busy || prompt ? false : 5000, refetchOnWindowFocus: !busy && !prompt });
  const job = detail.data?.job ?? created, review = detail.data?.review;
  const selectedProvider = job?.provider ?? provider;
  const discourse = selectedProvider === "DISCOURSE";
  const safe = !!review && review.fresh && review.eligible && review.activationAvailable && review.validationVersion === 2 && !!review.sourceOptions
    && Date.parse(review.expiresAt) > now && job?.state === "READY_TO_COMMIT" && !detail.isError;
  const required = ["PRIVATE_CONTENT", "INACTIVE_AUTHORS", ...(review?.warnings ?? [])];
  const acknowledged = !!review && ackDigest === review.reviewDigest && required.every(code => acks[code]);
  async function refresh() { const result = selected ? await detail.refetch() : await listing.refetch(); if (!result.isError) setUncertain(false); }
  function download(value: unknown, suffix: string) {
    const current = life.current, url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: "application/json" })); current.urls.add(url);
    const link = document.createElement("a"); link.href = url; link.download = `commonbeacon-import-${selected ?? "review"}-${suffix}.json`; document.body.appendChild(link); link.click(); link.remove();
    window.setTimeout(() => { URL.revokeObjectURL(url); current.urls.delete(url); }, 1000);
  }
  function choose(next?: File) {
    setError(""); if (next && (!next.name.toLowerCase().endsWith(discourse ? ".json" : ".zip") || next.size < 1 || next.size > (discourse ? 8388608 : 67108864))) { setFile(undefined); setFileVersion(v => v + 1); setError(discourse ? "Choose a nonempty .json bundle no larger than 8 MiB." : "Choose a nonempty .zip file no larger than 64 MiB."); return; }
    setFile(next);
  }
  async function sendFile(target: Job, input: File, current: typeof life.current) {
    if (target.state !== "UPLOADING") { if (current.active) navigate(`${base}/${target.id}`, { replace: true }); return; }
    upload.current = new AbortController(); setUploading(true); setSent(0);
    try { await uploadImport(target.id, input, AbortSignal.any([current.controller.signal, upload.current.signal]), n => { if (current.active) setSent(n); }, target.provider ?? "NATIVE");
      if (current.active) { setFile(undefined); setFileVersion(v => v + 1); navigate(`${base}/${target.id}`, { replace: true }); setNotice("Complete archive uploaded. Waiting for inspection."); }
    } finally { if (current.active) setUploading(false); }
  }
  async function authenticated(grant: RecentAuthGrant) {
    if (!prompt || busy) return; const action = prompt, current = life.current;
    setPrompt(undefined); setBusy(true); setError(""); setNotice("");
    try {
      if (Date.parse(grant.expiresAt) <= Date.now()) throw new ApiError("http", "Password confirmation expired. Confirm again.", 403, undefined, undefined, "RECENT_AUTH_REQUIRED");
      if (action.kind === "upload") {
        if (!file) return;
        const target = await createImport(grant.token, creationKey.current, current.controller.signal, selectedProvider); if (!current.active) return;
        setCreated(target); await sendFile(target, file, current);
      } else {
        await activateImport(action.job, action.review, grant.token, action.key, current.controller.signal);
        if (current.active) { setAcks({}); setNotice("Confirmation recorded. Check status for the committed outcome; cancellation is now closed."); }
      }
    } catch (e) { if (current.active) { failure(e); if (!(e instanceof ApiError && e.status && e.status < 500)) setUncertain(true);
      if (action.kind === "activate") { setAcks({}); setAckDigest(""); setNotice("Check status and review again. No updated review will be submitted automatically."); }
    } } finally { if (current.active) { setBusy(false); void client.invalidateQueries({ queryKey: ["imports", actorId] }); heading.current?.focus(); } }
  }
  async function mutate(action: "upload" | "review" | "cancel") {
    if (!job || busy) return; const current = life.current; setBusy(true); setError(""); setNotice("");
    try {
      if (action === "upload" && file) await sendFile(job, file, current);
      if (action === "review") { await dryRun(job, crypto.randomUUID(), current.controller.signal); if (current.active) { setAcks({}); setAckDigest(""); setIssuePage(0); } }
      if (action === "cancel") await cancelJob(job, crypto.randomUUID(), current.controller.signal);
    } catch (e) { if (current.active) { failure(e); setUncertain(true); } }
    finally { if (current.active) { setBusy(false); void client.invalidateQueries({ queryKey: ["imports", actorId] }); } }
  }
  function beginActivation() {
    if (!job || !review || !safe || !acknowledged || uncertain) return;
    if (confirmation.current.digest !== review.reviewDigest) confirmation.current = { digest: review.reviewDigest, key: crypto.randomUUID() };
    setPrompt({ kind: "activate", job, review, key: confirmation.current.key });
  }
  return <section className="board-page data-management import-screen">
    <p className="eyebrow">ADMINISTRATION</p><h1 ref={heading} tabIndex={-1}>{history ? "Import history" : "Import company data"}</h1>
    <nav className="article-actions" aria-label="Data transfer navigation"><Link to="/admin/data">Data management</Link>{!history && <Link to={`${base}/history`}>View import history</Link>}{selected && <Link to={base}>Start another import</Link>}{history && <Link to={base}>New import</Link>}</nav>
    {!history && <div className="transfer-warning"><strong>Import only into an empty community.</strong><p>The target must contain only active bootstrap administrators, with demo seeding disabled. Imported authors cannot sign in, regain roles, or claim matching accounts. Hidden content and unpublished articles remain private.</p>
      <p>Supported formats: CommonBeacon company archive v1 and CommonBeacon Discourse bundle v1 from Discourse 3.5.0. Personal archives and attachment files cannot be activated. Limits: native ZIP 64 MiB; Discourse JSON 8 MiB; 40,000 records total; 16 MiB staged JSON for activation. The atomic commit has a 30-second deadline.</p>
      <details><summary>Entity limits and omitted fields</summary><p>2,000 authors, 100 boards, 5,000 questions, 20,000 replies, 5,000 acceptances, 1,000 articles, 2,000 contacts, 5,000 reports and 5,000 actions, within the total limit.</p><p>Credentials, local permissions, sessions, grants, transfer bookkeeping and attachments are not restored. Search vectors rebuild and local edit versions restart at zero. Contacts and moderation history can only be included if selected in the original export; change those options by making a new source export.</p></details>
    </div>}
    {error && <p className="form-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {uncertain && <div className="transfer-warning"><p>The response was interrupted. The server may have accepted the request. Check its outcome before retrying. Activation is never resubmitted automatically.</p><Button disabled={busy} onClick={() => void refresh()}>Check outcome</Button></div>}
    {!history && (!selected || job?.state === "UPLOADING") && <section className="transfer-panel" aria-labelledby="upload-title"><h2 id="upload-title">Upload archive</h2>
      <label htmlFor="import-format">Archive format</label><select id="import-format" disabled={busy || !!prompt || !!selected || uncertain} value={selectedProvider} onChange={e => { setProvider(e.target.value as "NATIVE" | "DISCOURSE"); setFile(undefined); setFileVersion(v => v + 1); creationKey.current = crypto.randomUUID(); setError(""); }}><option value="NATIVE">CommonBeacon company archive v1 (.zip)</option><option value="DISCOURSE">Discourse 3.5.0 bundle v1 (.json)</option></select>
      {discourse && <p>Use scripts/discourse/export_commonbeacon.rb on Discourse 3.5.0. Bare category JSON and database backups are unsupported. Public categories only; review all conversion losses before activation.</p>}
      <label htmlFor="import-file">Archive file</label><input key={fileVersion} id="import-file" type="file" accept={discourse ? ".json,application/json" : ".zip,application/zip"} disabled={busy || !!prompt} onChange={e => choose(e.target.files?.[0])} />
      {file && <p>{file.name} — {(file.size / 1048576).toFixed(2)} MiB. The file stays in memory only.</p>}
      <p>Interrupted uploads restart the whole file. After navigation or refresh, reselect it only if the job still needs an upload. Private staging can resume on the server; live activation is one transaction.</p>
      <Button disabled={!file || busy || !!prompt || uncertain} onClick={() => job ? void mutate("upload") : setPrompt({ kind: "upload" })}>{job ? "Upload complete file" : "Confirm password and upload"}</Button>
    </section>}
    {uploading && <div role="status"><p>{sent === file?.size ? "Upload sent; waiting for server verification." : `Uploading ${sent.toLocaleString()} of ${file?.size.toLocaleString()} bytes.`}</p><progress aria-label="Archive upload progress" value={sent} max={file?.size ?? 1} /><Button onClick={() => upload.current?.abort()}>Stop upload</Button></div>}
    {prompt && <div className="transfer-panel"><p>{prompt.kind === "activate" ? "This confirms the exact review you acknowledged. Activation cannot be cancelled after the server accepts confirmation." : "Confirm your identity to create this private upload request."}</p><RecentAuthenticationPrompt actorId={actorId} scope={prompt.kind === "activate" ? "IMPORT_COMMIT" : "IMPORT_UPLOAD"} onConfirmed={g => void authenticated(g)} onCancel={() => setPrompt(undefined)} /></div>}
    {selected && <section className="transfer-panel" aria-labelledby="import-status"><h2 id="import-status">Import status</h2><p className="import-reference">Reference: {selected}</p>
      <Button disabled={busy || !!prompt || detail.isFetching} onClick={() => void refresh()}>Refresh status</Button>
      {detail.isError && <p role="alert">{detail.error instanceof ApiError && detail.error.code === "IMPORT_IN_PROGRESS" ? "Activation holds the write gate. Status will refresh when the transaction finishes." : message(detail.error)}</p>}
      {!job && !detail.isError && <p role="status">Loading import status...</p>}
      {job && <><p role="status">{states[job.state] ?? job.state}</p><p>{job.processedRecords.toLocaleString()} records processed at the latest checkpoint. This is not a committed count.</p>
        {job.errorCode && <p role="alert">Failure reference: {job.errorCode}. A failed activation leaves the target unchanged; start a new import after correcting the cause.</p>}
        {job.state === "COMMITTING" && <p>Confirmation is durable. Do not submit another import. Refresh or return to this URL to see the authoritative result.</p>}
        {job.allowedActions.includes("CANCEL") && <Button disabled={busy || !!prompt} onClick={() => void mutate("cancel")}>Cancel import</Button>}
        {!terminal(job) && job.state !== "COMMITTING" && <p>Cancellation is available until confirmation is accepted and the job becomes COMMITTING.</p>}
        {detail.data?.inspection && <div><p>{detail.data.inspection.valid ? "Archive inspection passed. Run the dry run to review target eligibility and mappings." : "Archive inspection failed. Cancel this request and choose a corrected archive."}</p><ul>{detail.data.inspection.issues.map((i, n) => <li key={n}>{i.file}, line {i.line}: {i.code}</li>)}</ul></div>}
        {["REVIEW_REQUIRED", "READY_TO_COMMIT"].includes(job.state) && <Button disabled={busy || !!prompt || uncertain || detail.isFetching} onClick={() => void mutate("review")}>{review ? "Run a fresh dry run" : "Run dry run"}</Button>}
      </>}
    </section>}
    {review && <section className="transfer-panel" aria-labelledby="review-title"><h2 id="review-title">Review before activation</h2>
      {!review.fresh && <p role="alert">This review is stale. Run a fresh dry run, inspect the changes, and acknowledge them again.</p>}
      {!review.eligible && <p role="alert">Activation is blocked. Correct the listed errors or use an empty bootstrap target.</p>}
      {!review.sourceOptions && <p role="alert">This older review lacks source scope details. Run a fresh dry run before confirming.</p>}
      <p className="import-reference">Archive SHA-256: {review.archiveDigest}</p><p className="import-reference">Review SHA-256: {review.reviewDigest}</p>
      <p>Native format {review.formatVersion ?? "unknown"}; validation {review.validationVersion}; mapping {review.mappingVersion}; source product {review.productVersion ?? "unknown"}.</p>
      <p>Source instance: <span className="import-reference">{review.sourceInstanceId ?? "Unavailable"}</span>. Review expires {new Date(review.expiresAt).toLocaleString()}.</p>
      <p>Staged JSON: {review.budget.stagedBytes.toLocaleString()} bytes; activation maximum: {review.budget.maxActivationBytes.toLocaleString()} bytes. Identity collisions: {review.identityCollisions} (accounts remain separate).</p>
      {review.sourceOptions && <p>Contact details: {review.sourceOptions.includeContacts ? "included as inactive attribution only" : "excluded"}. Private moderation history: {review.sourceOptions.includeModerationHistory ? "included as source history" : "excluded"}.</p>}
      <h3>Declared exclusions</h3><ul>{review.exclusions.map((x, n) => <li key={n}>{x}</li>)}</ul>
      {review.sourceWarnings.length > 0 && <><h3>Warnings declared by the source archive</h3><ul>{review.sourceWarnings.map((x, n) => <li key={n}>{x}</li>)}</ul></>}
      <ul>{review.warnings.map(w => <li key={w}>{warningText[w] ?? w}</li>)}</ul>
      <div className="import-counts">{entities.map(e => <p key={e}><strong>{e}</strong><span>{review.counts[e]} staged</span></p>)}</div>
      <h3>Validation errors ({review.totalErrors})</h3>{review.totalErrors === 0 ? <p>No validation errors.</p> : <><ol>{review.errors.slice(issuePage * 20, (issuePage + 1) * 20).map((e, n) => <li key={n}>{e.file}, line {e.line}: {e.code}</li>)}</ol><p>Showing up to 20 errors per page; the report retains at most 1,000.</p><Button disabled={!issuePage} onClick={() => setIssuePage(v => v - 1)}>Previous errors</Button><Button disabled={(issuePage + 1) * 20 >= review.errors.length} onClick={() => setIssuePage(v => v + 1)}>Next errors</Button></>}
      <details><summary>Source-to-local mapping preview (up to 100)</summary><Mappings rows={review.mappingPreview} discourse={discourse} /></details>
      <Button onClick={() => download(review, "review")}>Download review and errors JSON</Button>
      <fieldset disabled={!safe || busy || !!prompt || uncertain}><legend>Final acknowledgements</legend>
        {required.map(code => <label className="transfer-check" key={code}><input type="checkbox" checked={ackDigest === review.reviewDigest && !!acks[code]} onChange={e => { setAcks(previous => ({ ...(ackDigest === review.reviewDigest ? previous : {}), [code]: e.target.checked })); setAckDigest(review.reviewDigest); }} />{code === "PRIVATE_CONTENT" ? "I understand hidden content, drafts and private fields will be imported with their source visibility." : code === "INACTIVE_AUTHORS" ? "I understand imported authors cannot sign in or regain access." : warningText[code] ?? `I acknowledge: ${code}`}</label>)}
      </fieldset><Button disabled={!safe || !acknowledged || busy || !!prompt || uncertain} onClick={beginActivation}>Confirm reviewed import</Button>
    </section>}
    {detail.data?.result && <Result discourse={discourse} value={detail.data.result} download={() => download(detail.data!.result, "reconciliation-page")} next={() => setMappingAfter(detail.data!.result!.nextCursor)} first={() => setMappingAfter(null)} hasPrevious={!!mappingAfter} />}
    {!selected && <section className="transfer-panel"><h2>{history ? "Your import requests" : "Latest import"}</h2><p>Only your requests are shown. Open a request to resume or reconcile it. Status refreshes every five seconds.</p>
      {listing.isError && <p role="alert">{message(listing.error)}</p>}{listing.isPending && <p role="status">Loading requests...</p>}
      {listing.data?.items.length === 0 && <p>No import requests yet.</p>}
      <ol className="import-history">{listing.data?.items.slice(0, history ? 20 : 1).map(j => <li key={j.id}><Link to={`${base}/${j.id}`}>{states[j.state] ?? j.state}</Link><p>{new Date(j.createdAt).toLocaleString()}</p><p className="import-reference">{j.id}</p></li>)}</ol>
      {history && <><Button disabled={!after} onClick={() => setAfter(null)}>Newest requests</Button><Button disabled={!listing.data?.nextCursor} onClick={() => setAfter(listing.data!.nextCursor)}>Older requests</Button></>}
    </section>}
  </section>;
}
function Mappings({ rows, discourse = false }: { rows: Mapping[]; discourse?: boolean }) { return <ul className="import-mappings">{rows.map(m => <li key={`${m.entity}:${m.sourceId}`}><strong>{m.entity}</strong><span>Source: {m.sourceId}{discourse && ` (Discourse ${({ users: "user", boards: "category", questions: "topic", replies: "post" } as Record<string, string>)[m.entity] ?? m.entity} #${parseInt(m.sourceId.slice(-8), 16)})`}</span><span>Local: {m.localId}</span></li>)}</ul>; }
function Result({ value, download, next, first, hasPrevious, discourse }: { discourse: boolean; value: Reconciliation; download: () => void; next: () => void; first: () => void; hasPrevious: boolean }) {
  return <section className="transfer-panel" aria-labelledby="reconcile-title"><h2 id="reconcile-title">Import reconciliation</h2><p>{value.state === "COMPLETED" ? "The complete reviewed dataset was committed atomically. These are historical creation counts, not counts of records that may have been edited later." : "No imported records became live. Cancelled records are counted as skipped; failed records as rejected/uncommitted."}</p>
    <p>Expected counts refer to reviewed/staged records. No successful import silently skips records. Excluded source fields were never part of these counts.</p>
    {!value.detailsAvailable && <p>Detailed review data was erased, expired or never produced. Historical completion counts remain available when retained; unknown counts are shown as unavailable.</p>}
    <div className="import-counts">{entities.map(e => { const c = value.counts[e]; return <div key={e}><h3>{e}</h3><dl><dt>Expected</dt><dd>{c.expected ?? "Unavailable"}</dd><dt>Created</dt><dd>{c.created}</dd><dt>Skipped</dt><dd>{c.skipped ?? "Unavailable"}</dd><dt>Rejected</dt><dd>{c.rejected ?? "Unavailable"}</dd></dl></div>; })}</div>
    <Button onClick={download}>Download reconciliation JSON (this mapping page)</Button>
    <details><summary>Authorized source-to-local references (100 per page)</summary><Mappings rows={value.mappings} discourse={discourse} /><Button disabled={!hasPrevious} onClick={first}>First mapping page</Button><Button disabled={!value.nextCursor} onClick={next}>Next mapping page</Button></details>
  </section>;
}
