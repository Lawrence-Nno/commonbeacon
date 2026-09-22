package com.lawrencenno.commonbeacon.transfer.job;

import static com.lawrencenno.commonbeacon.transfer.job.TransferJob.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal persistence boundary, not an HTTP authorization or recent-authentication API.
 * Short transactions lock control -> job -> requester. Work/file I/O always happens outside. */
@Service
public class TransferJobs {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final String TERMINAL = "'READY','COMPLETED','FAILED','CANCELLED'";
    private static final RowMapper<TransferJob> ROW = (r, n) -> new TransferJob(
        r.getObject("id",UUID.class),r.getObject("requester_id",UUID.class),Kind.valueOf(r.getString("kind")),
        State.valueOf(r.getString("state")),r.getLong("version"),r.getLong("fence"),r.getInt("attempts"),
        r.getObject("worker_id",UUID.class),r.getTimestamp("lease_until") == null ? null : r.getTimestamp("lease_until").toInstant(),r.getLong("checkpoint"),
        r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getTimestamp("expires_at").toInstant(),
        r.getString("error_code")==null ? null : Failure.valueOf(r.getString("error_code")));
    public TransferJobs(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }
    private <T> T locked(Supplier<T> action) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.queryForObject("SELECT id FROM transfer_control WHERE id=1 FOR UPDATE", Integer.class);
            return action.get();
        });
    }
    private TransferJob job(UUID id) {
        return jdbc.query("SELECT * FROM transfer_job WHERE id=? FOR UPDATE", ROW, id).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("JOB_NOT_FOUND"));
    }
    private boolean permitted(TransferJob j) {
        if(!permitted(j.requester(),j.kind()))return false;
        return jdbc.queryForObject("SELECT j.authorization_revision=u.auth_revision FROM transfer_job j JOIN app_user u ON u.id=j.requester_id WHERE j.id=?",Boolean.class,j.id());
    }
    private boolean permitted(UUID requester, Kind kind) {
        var roles = jdbc.queryForList("SELECT role FROM app_user WHERE id=? AND account_state='ACTIVE' FOR SHARE", String.class, requester);
        return !roles.isEmpty() && (kind == Kind.PERSONAL_EXPORT || roles.getFirst().equals("ADMINISTRATOR"));
    }
    private TransferJob owned(UUID actor, UUID id) {
        var j = job(id);
        if (!j.requester().equals(actor)) throw new IllegalStateException("JOB_NOT_FOUND");
        if (!permitted(j.requester(),j.kind())) throw new IllegalStateException("FORBIDDEN");
        return j;
    }
    private void audit(UUID id, Event event) {
        jdbc.update("INSERT INTO transfer_audit(job_id,event) VALUES (?,?)",id,event.name());
    }
    private void endAttempt(UUID id) {
        jdbc.update("UPDATE transfer_attempt SET finished_at=clock_timestamp() WHERE job_id=? AND finished_at IS NULL",id);
    }
    private void fail(TransferJob j, Failure failure) {
        jdbc.update("UPDATE transfer_job SET state='FAILED',error_code=?,version=version+1,worker_id=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",failure.name(),j.id());
        endAttempt(j.id()); audit(j.id(),Event.FAILED);
    }
    public TransferJob create(UUID actor, Kind kind, UUID requestKey, String payloadHash) {
        return create(actor,kind,requestKey,payloadHash,false,false,()->{});
    }
    public TransferJob createCompanyExport(UUID actor, UUID key, boolean contacts, boolean history, Runnable authorize) {
        String hash=java.util.HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256()
            .digest(("company:1:"+contacts+":"+history+":true").getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return create(actor,Kind.COMPANY_EXPORT,key,hash,contacts,history,authorize);
    }
    private TransferJob create(UUID actor,Kind kind,UUID requestKey,String payloadHash,boolean contacts,boolean history,Runnable authorize) {
        if (payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_REQUEST_HASH");
        Objects.requireNonNull(actor); Objects.requireNonNull(kind); Objects.requireNonNull(requestKey);
        return locked(() -> {
            if (!permitted(actor,kind)) throw new IllegalStateException("FORBIDDEN");
            jdbc.update("DELETE FROM transfer_request WHERE expires_at<=clock_timestamp()");
            var requests = jdbc.queryForList("SELECT job_id,request_hash FROM transfer_request WHERE requester_id=? AND operation=? AND request_key=?",actor,kind.name(),requestKey);
            if (!requests.isEmpty()) {
                if (!requests.getFirst().get("request_hash").equals(payloadHash)) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                return job((UUID)requests.getFirst().get("job_id"));
            }
            if (jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE state NOT IN ("+TERMINAL+")",Long.class)>=10
                || jdbc.queryForObject("SELECT coalesce(sum(reserved_bytes),0) FROM transfer_job",Long.class)+805306368L>2147483648L)
                throw new IllegalStateException("TRANSFER_QUOTA_EXCEEDED");
            if (jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE requester_id=? AND state NOT IN ("+TERMINAL+")",Long.class,actor)>0)
                throw new IllegalStateException("ACTIVE_JOB_EXISTS");
            authorize.run();
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO transfer_job(id,requester_id,kind,state,expires_at,authorization_revision,include_contacts,include_moderation_history) VALUES (?,?,?,?,clock_timestamp()+? * interval '1 minute',(SELECT auth_revision FROM app_user WHERE id=?),?,?)",
                id,actor,kind.name(),kind==Kind.COMPANY_IMPORT?"UPLOADING":"QUEUED",kind==Kind.COMPANY_IMPORT?60:30,actor,contacts,history);
            jdbc.update("INSERT INTO transfer_request VALUES (?,?,?,?,?,clock_timestamp()+interval '24 hours')",actor,kind.name(),requestKey,payloadHash,id);
            audit(id,Event.CREATED); return job(id);
        });
    }
    public Optional<TransferJob> claim(UUID worker) {
        return claim(worker,true,false);
    }
    public Optional<TransferJob> claimExport(UUID worker) {
        return claim(worker,false,false);
    }
    public Optional<TransferJob> claimCompanyExport(UUID worker) {return claim(worker,false,true);}
    private Optional<TransferJob> claim(UUID worker,boolean includeImports,boolean companyOnly) {
        Objects.requireNonNull(worker);
        return locked(() -> {
            recoverLocked();
            if (jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE lease_until>clock_timestamp()",Long.class)>0) return Optional.empty();
            var candidates=jdbc.query("SELECT * FROM transfer_job WHERE state IN ('QUEUED','RUNNING','VALIDATING') AND worker_id IS NULL AND (? OR kind<>'COMPANY_IMPORT') AND (NOT ? OR kind='COMPANY_EXPORT') ORDER BY created_at,id LIMIT 10 FOR UPDATE",ROW,includeImports,companyOnly);
            for (var j:candidates) {
                if (!permitted(j)) { fail(j,Failure.AUTHORIZATION_REVOKED); continue; }
                if (j.attempts()>=3) { fail(j,Failure.ATTEMPTS_EXHAUSTED); continue; }
                jdbc.update("UPDATE transfer_job SET state=CASE WHEN kind='COMPANY_IMPORT' THEN 'VALIDATING' ELSE 'RUNNING' END,worker_id=?,lease_until=clock_timestamp()+interval '60 seconds',fence=fence+1,attempts=attempts+1,version=version+1,checkpoint=CASE WHEN kind='COMPANY_IMPORT' THEN checkpoint ELSE 0 END,updated_at=clock_timestamp(),expires_at=clock_timestamp()+interval '10 minutes' WHERE id=?",worker,j.id());
                var claimed=job(j.id());
                jdbc.update("INSERT INTO transfer_attempt(job_id,fence,worker_id) VALUES (?,?,?)",j.id(),claimed.fence(),worker);
                audit(j.id(),Event.CLAIMED); return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }
    private void recoverLocked() {
        for (var j:jdbc.query("SELECT * FROM transfer_job WHERE state NOT IN ("+TERMINAL+") AND (expires_at<=clock_timestamp() OR lease_until<=clock_timestamp()) FOR UPDATE",ROW)) {
            if (j.state()==State.COMMITTING) { fail(j,Failure.ACTIVATION_RECONCILIATION_REQUIRED); continue; }
            boolean expired=jdbc.queryForObject("SELECT expires_at<=clock_timestamp() FROM transfer_job WHERE id=?",Boolean.class,j.id());
            if (expired) { audit(j.id(),Event.EXPIRED); fail(j,Failure.JOB_EXPIRED); continue; }
            endAttempt(j.id()); audit(j.id(),Event.LEASE_EXPIRED);
            if (j.attempts()>=3) fail(j,Failure.ATTEMPTS_EXHAUSTED);
            else jdbc.update("UPDATE transfer_job SET worker_id=NULL,lease_until=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=?",j.id());
        }
    }
    private TransferJob current(Lease lease) {
        if(lease.worker()==null || lease.fence()<1)throw new IllegalStateException("STALE_LEASE");
        var j=job(lease.jobId());
        if (!Objects.equals(j.worker(),lease.worker()) || j.fence()!=lease.fence() || j.terminal()
            || !jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM transfer_job WHERE id=?",Boolean.class,j.id()))
            throw new IllegalStateException("STALE_LEASE");
        if (!permitted(j)) { fail(j,Failure.AUTHORIZATION_REVOKED); return null; }
        if (jdbc.queryForObject("SELECT expires_at<=clock_timestamp() FROM transfer_job WHERE id=?",Boolean.class,j.id())) {
            fail(j,Failure.JOB_EXPIRED); return null;
        }
        return j;
    }
    public boolean heartbeat(Lease lease) {
        return locked(() -> {
            if (current(lease)==null) return false;
            jdbc.update("UPDATE transfer_job SET lease_until=clock_timestamp()+interval '60 seconds' WHERE id=?",lease.jobId()); return true;
        });
    }
    public boolean checkpoint(Lease lease,long offset) {
        if (offset<0) throw new IllegalArgumentException("INVALID_CHECKPOINT");
        return locked(() -> {
            var j=current(lease); if(j==null)return false;
            if(offset<j.checkpoint())throw new IllegalStateException("STALE_CHECKPOINT");
            jdbc.update("UPDATE transfer_job SET checkpoint=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",offset,j.id());return true;
        });
    }
    public boolean fail(Lease lease,Failure failure) {
        return locked(() -> {var j=current(lease);if(j==null)return false;fail(j,failure);return true;});
    }
    public TransferJob cancel(UUID actor,UUID id,long version) {
        return locked(() -> {
            var j=owned(actor,id); if(j.state()==State.CANCELLED)return j;
            if(j.version()!=version || j.terminal() || j.state()==State.COMMITTING)throw new IllegalStateException("JOB_CONFLICT");
            jdbc.update("UPDATE transfer_job SET state='CANCELLED',version=version+1,worker_id=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",id);
            endAttempt(id);audit(id,Event.CANCELLED);return job(id);
        });
    }
    public TransferJob status(UUID actor,UUID id) { return locked(() -> owned(actor,id)); }
    public List<TransferJob> list(UUID actor,Instant before,UUID beforeId,int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("INVALID_PAGE_SIZE");
        return locked(() -> {
            var rows=jdbc.query("SELECT * FROM transfer_job WHERE requester_id=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",ROW,actor,Timestamp.from(before),beforeId,limit);
            return rows.stream().filter(j->permitted(j.requester(),j.kind())).toList();
        });
    }
    public UUID beginArtifact(Lease lease,String purpose,long limit) {
        if(!Set.of("INTERMEDIATE","DOWNLOAD").contains(purpose) || limit<1 || limit>(purpose.equals("DOWNLOAD")?67108864L:268435456L))throw new IllegalArgumentException("INVALID_ARTIFACT");
        return locked(() -> {
            var j=current(lease);if(j==null)return null;
            long allocated=jdbc.queryForObject("SELECT coalesce(sum(byte_limit),0) FROM transfer_artifact WHERE job_id=? AND state NOT IN ('DELETED','MISSING')",Long.class,j.id());
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE job_id=? AND state NOT IN ('DELETED','MISSING')",Long.class,j.id())>=10)
                throw new IllegalStateException("ARTIFACT_COUNT_LIMIT");
            if(allocated+limit>805306368L)throw new IllegalStateException("TRANSFER_QUOTA_EXCEEDED");
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO transfer_artifact(id,job_id,fence,purpose,byte_limit) VALUES (?,?,?,?,?)",id,j.id(),lease.fence(),purpose,limit);return id;
        });
    }
    public boolean publish(Lease lease,UUID artifact,long bytes,String hash) {
        if(bytes<0 || hash==null || !hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_ARTIFACT");
        return locked(() -> {
            var j=job(lease.jobId());
            // A lost success response must not publish or audit a second time.
            if(j.state()==State.READY && j.fence()==lease.fence() && permitted(j))
                return jdbc.queryForObject("SELECT count(*) FROM transfer_completion c JOIN transfer_attempt a USING(job_id,fence) WHERE c.job_id=? AND c.fence=? AND c.artifact_id=? AND a.worker_id=?",Long.class,j.id(),lease.fence(),artifact,lease.worker())==1;
            j=current(lease);if(j==null)return false;
            if(j.kind()==Kind.COMPANY_IMPORT)throw new IllegalStateException("IMPORT_ACTIVATION_NOT_IMPLEMENTED");
            int changed=jdbc.update("UPDATE transfer_artifact SET state='AVAILABLE',byte_count=?,sha256=?,expires_at=clock_timestamp()+interval '24 hours' WHERE id=? AND job_id=? AND fence=? AND purpose='DOWNLOAD' AND state='WRITING' AND byte_limit>=?",bytes,hash,artifact,j.id(),lease.fence(),bytes);
            if(changed!=1)throw new IllegalStateException("ARTIFACT_CONFLICT");
            jdbc.update("INSERT INTO transfer_completion(job_id,fence,artifact_id) VALUES (?,?,?)",j.id(),lease.fence(),artifact);
            jdbc.update("UPDATE transfer_job SET state='READY',worker_id=NULL,lease_until=NULL,version=version+1,updated_at=clock_timestamp(),expires_at=clock_timestamp()+interval '24 hours' WHERE id=?",j.id());
            endAttempt(j.id());audit(j.id(),Event.COMPLETED);return true;
        });
    }
    public boolean map(Lease lease,String entity,UUID source,UUID local) {
        if(!Set.of("users","boards","questions","replies","articles","reports","actions").contains(entity))throw new IllegalArgumentException("INVALID_ENTITY");
        return locked(() -> {
            var j=current(lease);if(j==null)return false;
            if(j.kind()!=Kind.COMPANY_IMPORT || j.state()!=State.VALIDATING)throw new IllegalStateException("JOB_CONFLICT");
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_mapping WHERE job_id=?",Long.class,j.id())>=40000)throw new IllegalStateException("MAPPING_LIMIT");
            jdbc.update("INSERT INTO transfer_mapping(job_id,entity,source_id,local_id,fence) VALUES (?,?,?,?,?) ON CONFLICT(job_id,entity,source_id) DO NOTHING",j.id(),entity,source,local,lease.fence());
            if(!local.equals(jdbc.queryForObject("SELECT local_id FROM transfer_mapping WHERE job_id=? AND entity=? AND source_id=?",UUID.class,j.id(),entity,source)))throw new IllegalStateException("MAPPING_CONFLICT");
            return true;
        });
    }
    public void recordDownload(UUID actor,UUID id,boolean delivered) {
        locked(() -> {var j=owned(actor,id);if(j.state()!=State.READY)throw new IllegalStateException("JOB_CONFLICT");audit(id,delivered?Event.DOWNLOAD_COMPLETED:Event.DOWNLOAD_ATTEMPT);return null;});
    }
    public record Artifact(UUID id,UUID jobId,String state,long fence,Instant createdAt,Long bytes,String hash) {}
    public List<Artifact> artifacts() {
        return jdbc.query("SELECT id,job_id,state,fence,created_at,byte_count,sha256 FROM transfer_artifact WHERE state<>'DELETED' ORDER BY created_at,id LIMIT 10000",(r,n)->new Artifact(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getTimestamp(5).toInstant(),r.getObject(6,Long.class),r.getString(7)));
    }
    public Set<UUID> knownArtifactKeys() {return new HashSet<>(jdbc.queryForList("SELECT id FROM transfer_artifact WHERE state<>'DELETED'",UUID.class));}
    public boolean needsCleanup(UUID artifact,boolean fileExists) {
        return locked(() -> {
            recoverLocked();
            var rows=jdbc.queryForList("SELECT * FROM transfer_artifact WHERE id=? FOR UPDATE",artifact);if(rows.isEmpty())return false;
            var a=rows.getFirst();var j=job((UUID)a.get("job_id"));String state=(String)a.get("state");
            if(state.equals("DELETED"))return false;
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_download WHERE artifact_id=? AND finished_at IS NULL AND expires_at>clock_timestamp()",Long.class,artifact)>0)return false;
            boolean expired=jdbc.queryForObject("SELECT expires_at<=clock_timestamp() FROM transfer_artifact WHERE id=?",Boolean.class,artifact);
            if(state.equals("AVAILABLE") && !fileExists) {
                jdbc.update("UPDATE transfer_artifact SET state='MISSING' WHERE id=?",artifact);audit(j.id(),Event.FAILED);
                jdbc.update("UPDATE transfer_job SET error_code='ARTIFACT_MISSING',version=version+1,updated_at=clock_timestamp() WHERE id=?",j.id());
                // Keep retained job outcome/ledger; artifact availability is separate.
                return true;
            }
            boolean abandoned=state.equals("WRITING") && (j.terminal() || j.worker()==null || j.fence()!=((Number)a.get("fence")).longValue());
            if(expired || abandoned || state.equals("DELETING") || state.equals("MISSING")) {
                if(expired && !state.equals("DELETING"))audit(j.id(),Event.EXPIRED);
                if(!state.equals("DELETING"))audit(j.id(),Event.CLEANUP_STARTED);
                jdbc.update("UPDATE transfer_artifact SET state='DELETING' WHERE id=?",artifact);return true;
            }
            return false;
        });
    }
    public void cleaned(UUID artifact) {
        locked(() -> {
            var rows=jdbc.queryForList("SELECT job_id FROM transfer_artifact WHERE id=? AND state IN ('DELETING','MISSING') FOR UPDATE",artifact);
            if(!rows.isEmpty()) {jdbc.update("UPDATE transfer_artifact SET state='DELETED' WHERE id=?",artifact);audit((UUID)rows.getFirst().get("job_id"),Event.CLEANUP_COMPLETED);}return null;
        });
    }
    public TransferJob status(UUID actor,UUID id,boolean personal) {
        return locked(() -> {
            var j=job(id);
            if(!j.requester().equals(actor) || (j.kind()==Kind.PERSONAL_EXPORT)!=personal)throw new IllegalStateException("JOB_NOT_FOUND");
            return owned(actor,id);
        });
    }
    public List<TransferJob> list(UUID actor,Instant before,UUID beforeId,int limit,boolean personal) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("INVALID_PAGE_SIZE");
        return locked(() -> {
            if(!permitted(actor,personal?Kind.PERSONAL_EXPORT:Kind.COMPANY_EXPORT))throw new IllegalStateException("FORBIDDEN");
            return jdbc.query("SELECT * FROM transfer_job WHERE requester_id=? AND (kind='PERSONAL_EXPORT')=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",ROW,actor,personal,Timestamp.from(before),beforeId,limit+1);
        });
    }
    public TransferJob cancel(UUID actor,UUID id,long version,UUID key,boolean personal) {
        return locked(() -> {
            var j=job(id);
            if(!j.requester().equals(actor) || (j.kind()==Kind.PERSONAL_EXPORT)!=personal)throw new IllegalStateException("JOB_NOT_FOUND");
            owned(actor,id);
            jdbc.update("DELETE FROM transfer_request WHERE expires_at<=clock_timestamp()");
            String hash=java.util.HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256()
                .digest((id+":"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var prior=jdbc.queryForList("SELECT request_hash FROM transfer_request WHERE requester_id=? AND operation='CANCEL' AND request_key=?",String.class,actor,key);
            if(!prior.isEmpty()) {
                if(!prior.getFirst().equals(hash))throw new IllegalStateException("IDEMPOTENCY_CONFLICT");return j;
            }
            if(j.state()!=State.CANCELLED) {
                if(j.version()!=version || j.terminal() || j.state()==State.COMMITTING)throw new IllegalStateException("JOB_CONFLICT");
                jdbc.update("UPDATE transfer_job SET state='CANCELLED',version=version+1,worker_id=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",id);
                endAttempt(id);audit(id,Event.CANCELLED);
            }
            jdbc.update("INSERT INTO transfer_request VALUES (?,'CANCEL',?,?,?,clock_timestamp()+interval '24 hours')",actor,key,hash,id);
            return job(id);
        });
    }
    public record Download(UUID artifact,long bytes,String hash,UUID lease) {}
    private Download available(UUID actor,UUID id) {
        var j=owned(actor,id);
        if(j.state()!=State.READY)throw new IllegalStateException("JOB_CONFLICT");
        var files=jdbc.query("SELECT a.id,a.byte_count,a.sha256 FROM transfer_completion c JOIN transfer_artifact a ON a.id=c.artifact_id WHERE c.job_id=? AND a.state='AVAILABLE' AND a.expires_at>clock_timestamp()",
            (r,n)->new Download(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),null),id);
        if(files.isEmpty())throw new IllegalStateException("ARTIFACT_EXPIRED");return files.getFirst();
    }
    public boolean artifactAvailable(UUID actor,UUID id) {
        return locked(() -> {
            var j=owned(actor,id);if(j.state()!=State.READY)return false;
            return jdbc.queryForObject("SELECT count(*) FROM transfer_completion c JOIN transfer_artifact a ON a.id=c.artifact_id WHERE c.job_id=? AND a.state='AVAILABLE' AND a.expires_at>clock_timestamp()",Long.class,id)>0;
        });
    }
    public Download downloadInfo(UUID actor,UUID id) {return locked(() -> available(actor,id));}
    public Download beginDownload(UUID actor,UUID id) {
        return locked(() -> {
            var file=available(actor,id);
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_download WHERE job_id=? AND finished_at IS NULL AND expires_at>clock_timestamp()",Long.class,id)>0)throw new IllegalStateException("DOWNLOAD_IN_PROGRESS");
            UUID lease=UUID.randomUUID();jdbc.update("INSERT INTO transfer_download(id,job_id,artifact_id) VALUES (?,?,?)",lease,id,file.artifact());
            audit(id,Event.DOWNLOAD_ATTEMPT);return new Download(file.artifact(),file.bytes(),file.hash(),lease);
        });
    }
    public boolean downloadLive(UUID lease) {return jdbc.queryForObject("SELECT count(*) FROM transfer_download WHERE id=? AND finished_at IS NULL AND expires_at>clock_timestamp()",Long.class,lease)>0;}
    public void finishDownload(UUID actor,UUID id,UUID lease,boolean delivered) {
        locked(() -> {
            var j=job(id);if(!j.requester().equals(actor))throw new IllegalStateException("JOB_NOT_FOUND");
            int changed=jdbc.update("UPDATE transfer_download SET finished_at=clock_timestamp() WHERE id=? AND job_id=? AND finished_at IS NULL",lease,id);
            if(changed==1 && delivered && permitted(actor,j.kind()))audit(id,Event.DOWNLOAD_COMPLETED);return null;
        });
    }
    public void releaseCleanedReservations() {
        locked(() -> {recoverLocked();jdbc.update("DELETE FROM transfer_mapping m USING transfer_job j WHERE m.job_id=j.id AND j.state IN ("+TERMINAL+")");
            jdbc.update("UPDATE transfer_job j SET reserved_bytes=0 WHERE state IN ("+TERMINAL+") AND NOT EXISTS(SELECT 1 FROM transfer_artifact a WHERE a.job_id=j.id AND a.state<>'DELETED')");return null;});
    }
}
