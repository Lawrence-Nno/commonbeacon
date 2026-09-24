package com.lawrencenno.commonbeacon.transfer.job;

import com.lawrencenno.commonbeacon.shared.MigrationGate;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.lawrencenno.commonbeacon.transfer.job.TransferJob.*;

/** One transaction publishes all domain groups and the immutable mapping ledger. */
@Service
public class ImportActivation {
    public static final long MAX_BYTES=16777216; // Fixed envelope; never an operator-tunable shortcut.
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ImportActivation.class);
    @FunctionalInterface public interface Probe {void after(String phase);}
    private final JdbcTemplate jdbc;private final TransferJobs jobs;private final ImportDryRuns dryRuns;
    private final TransactionTemplate transaction;private final ObjectProvider<ArtifactStore> stores;
    private final ObjectProvider<Probe> probes;private final boolean enabled;
    public ImportActivation(JdbcTemplate jdbc,TransferJobs jobs,ImportDryRuns dryRuns,PlatformTransactionManager manager,
            ObjectProvider<ArtifactStore> stores,ObjectProvider<Probe> probes,
            @Value("${commonbeacon.transfer.import.activation.enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.jobs=jobs;this.dryRuns=dryRuns;this.stores=stores;this.probes=probes;this.enabled=enabled;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(30);
    }
    public record Confirmation(long expectedVersion,String reviewDigest,String archiveDigest,long targetGeneration,
            boolean acknowledgedPrivateContent,boolean acknowledgedInactiveAuthors,Set<String> acknowledgedWarnings) {}
    private String requestHash(UUID id,Confirmation c) {
        return ImportDryRuns.hash(id+":"+c.expectedVersion()+":"+c.reviewDigest()+":"+c.archiveDigest()+":"+c.targetGeneration()+":"+
            c.acknowledgedPrivateContent()+":"+c.acknowledgedInactiveAuthors()+":"+new TreeSet<>(c.acknowledgedWarnings()));
    }
    public TransferJob confirm(UUID actor,UUID id,UUID key,Confirmation body,Runnable authorize) {
        if(!enabled || stores.getIfAvailable()==null)throw new IllegalStateException("TRANSFER_UNAVAILABLE");
        return jobs.locked(()->{
            jobs.recoverLocked();var j=jobs.ownedImport(actor,id);String hash=requestHash(id,body);
            var replay=jdbc.queryForList("SELECT request_key,request_hash FROM transfer_activation WHERE job_id=?",id);
            if(!replay.isEmpty()) {
                if(!key.equals(replay.getFirst().get("request_key")) || !hash.equals(replay.getFirst().get("request_hash")))throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                return j; // No grant consumption and no inserts, including after completion/cleanup.
            }
            if(j.state()!=State.READY_TO_COMMIT || j.version()!=body.expectedVersion())throw new IllegalStateException("JOB_CONFLICT");
            var prior=jdbc.queryForList("SELECT job_id FROM transfer_request WHERE requester_id=? AND operation='CONFIRM' AND request_key=? AND expires_at>clock_timestamp()",UUID.class,actor,key);
            if(!prior.isEmpty())throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            var report=review(id);
            if(!body.reviewDigest().equals(report.path("reviewDigest").asText()) || !body.archiveDigest().equals(report.path("archiveDigest").asText()) || body.targetGeneration()!=report.path("targetGeneration").asLong())throw new IllegalStateException("JOB_CONFLICT");
            fresh(id,report);
            var required=new TreeSet<String>();report.path("warnings").forEach(w->required.add(w.asText()));
            if(!body.acknowledgedPrivateContent() || !body.acknowledgedInactiveAuthors() || !required.equals(body.acknowledgedWarnings()))throw new IllegalStateException("JOB_CONFLICT");
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE lease_until>clock_timestamp() OR state='COMMITTING'",Long.class)>0)throw new IllegalStateException("JOB_CONFLICT");
            authorize.run();
            jdbc.update("DELETE FROM transfer_request WHERE expires_at<=clock_timestamp()");
            jdbc.update("INSERT INTO transfer_activation(job_id,request_key,request_hash,review) VALUES (?,?,?,?::jsonb)",id,key,hash,report.toString());
            jdbc.update("INSERT INTO transfer_request VALUES (?,'CONFIRM',?,?,?,clock_timestamp()+interval '24 hours')",actor,key,hash,id);
            jdbc.update("UPDATE transfer_job SET state='COMMITTING',version=version+1,attempts=0,expires_at=least(expires_at,clock_timestamp()+interval '5 minutes'),updated_at=clock_timestamp() WHERE id=?",id);
            jobs.audit(id,Event.CONFIRMED);return jobs.job(id);
        });
    }
    private JsonNode review(UUID id) {
        var rows=jdbc.queryForList("SELECT report::text FROM transfer_dry_run WHERE job_id=? AND status='REVIEWED' AND expires_at>clock_timestamp()",String.class,id);
        if(rows.isEmpty())throw new IllegalStateException("JOB_CONFLICT");return JSON.readTree(rows.getFirst());
    }
    private void fresh(UUID id,JsonNode report) {
        if(!report.path("eligible").asBoolean() || !report.path("fresh").asBoolean() || report.path("validationVersion").asInt()!=2 || report.path("mappingVersion").asInt()!=1
            || !report.path("reviewDigest").asText().equals(dryRuns.reviewDigest(report))
            || !report.path("target").path("digest").asText().equals(dryRuns.target().path("digest").asText())
            || !report.path("stagingDigest").asText().equals(dryRuns.stagingDigest(id))
            || !report.path("manifestDigest").asText().equals(dryRuns.manifestDigest(id)))throw new IllegalStateException("JOB_CONFLICT");
        var totals=jdbc.queryForMap("SELECT count(*) AS rows,coalesce(sum(byte_count),0) AS bytes FROM transfer_stage WHERE job_id=?",id);
        if(((Number)totals.get("rows")).longValue()>40000 || ((Number)totals.get("bytes")).longValue()>MAX_BYTES)throw new IllegalStateException("TRANSFER_QUOTA_EXCEEDED");
        if(((Number)totals.get("bytes")).longValue()!=report.path("budget").path("stagedBytes").asLong())throw new IllegalStateException("JOB_CONFLICT");
        if(jdbc.queryForObject("SELECT count(*) FROM imported_record",Long.class)!=0 || jdbc.queryForObject("SELECT count(*) FROM imported_author",Long.class)!=0)throw new IllegalStateException("JOB_CONFLICT");
    }
    public Optional<TransferJob> claim() {
        return jobs.locked(()->{
            jobs.recoverLocked();if(jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE lease_until>clock_timestamp()",Long.class)>0)return Optional.empty();
            var rows=jdbc.query("SELECT j.* FROM transfer_job j JOIN transfer_activation a ON a.job_id=j.id WHERE j.state='COMMITTING' AND j.worker_id IS NULL AND a.expires_at>clock_timestamp() ORDER BY j.created_at LIMIT 1 FOR UPDATE OF j",TransferJobs.ROW);
            if(rows.isEmpty())return Optional.empty();var j=rows.getFirst();
            if(!jobs.permitted(j)){jobs.fail(j,Failure.AUTHORIZATION_REVOKED);return Optional.empty();}
            UUID worker=UUID.randomUUID();
            jdbc.update("UPDATE transfer_job SET worker_id=?,fence=fence+1,attempts=1,lease_until=clock_timestamp()+interval '60 seconds',version=version+1,updated_at=clock_timestamp() WHERE id=?",worker,j.id());
            j=jobs.job(j.id());jdbc.update("INSERT INTO transfer_attempt(job_id,fence,worker_id) VALUES (?,?,?)",j.id(),j.fence(),worker);jobs.audit(j.id(),Event.CLAIMED);return Optional.of(j);
        });
    }
    private void boundary(String phase,long deadline) {
        var probe=probes.getIfAvailable();if(probe!=null)probe.after(phase);
        if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted())throw new IllegalStateException("ACTIVATION_TIMEOUT");
    }
    public void activate(Lease lease) {
        long deadline=System.nanoTime()+30_000_000_000L;
        transaction.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL transaction_timeout='30s'");jdbc.execute("SET LOCAL statement_timeout='25s'");jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SELECT pg_advisory_xact_lock("+MigrationGate.KEY+")");
            jdbc.queryForObject("SELECT id FROM transfer_control WHERE id=1 FOR UPDATE",Integer.class);
            var j=jobs.current(lease);if(j==null || j.state()!=State.COMMITTING)throw new IllegalStateException("JOB_CONFLICT");
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_completion WHERE job_id=?",Long.class,j.id())>0)throw new IllegalStateException("JOB_CONFLICT");
            var saved=jdbc.queryForList("SELECT review::text FROM transfer_activation WHERE job_id=? AND expires_at>clock_timestamp()",String.class,j.id());
            if(saved.isEmpty())throw new IllegalStateException("JOB_CONFLICT");
            var report=review(j.id());if(!report.equals(JSON.readTree(saved.getFirst())))throw new IllegalStateException("JOB_CONFLICT");
            fresh(j.id(),report);
            var files=jdbc.queryForList("SELECT a.id,a.byte_count,a.sha256 FROM transfer_dry_run d JOIN transfer_artifact a ON a.id=d.artifact_id WHERE d.job_id=? AND a.state='AVAILABLE' AND a.expires_at>clock_timestamp() AND a.sha256=d.archive_sha256",j.id());
            if(files.isEmpty())throw new IllegalStateException("ARTIFACT_EXPIRED");var file=files.getFirst();
            if(!report.path("archiveDigest").asText().equals(file.get("sha256")))throw new IllegalStateException("JOB_CONFLICT");
            try(var input=Objects.requireNonNull(stores.getIfAvailable()).open((UUID)file.get("id"))) {
                var digest=ArchiveCodec.sha256();byte[] buffer=new byte[65536];int n;long bytes=0;
                while((n=input.read(buffer))!=-1){bytes+=n;if(bytes>67108864 || System.nanoTime()>deadline)throw new IOException("ARCHIVE_LIMIT");digest.update(buffer,0,n);}
                if(bytes!=((Number)file.get("byte_count")).longValue() || !HexFormat.of().formatHex(digest.digest()).equals(file.get("sha256")))throw new IOException("ARCHIVE_CHANGED");
            }catch(IOException e){throw new UncheckedIOException(e);}
            boundary("validated",deadline);
            publish(j.id(),deadline);
            jdbc.update("INSERT INTO transfer_completion(job_id,fence) VALUES (?,?)",j.id(),j.fence());
            jdbc.update("UPDATE transfer_job SET state='COMPLETED',worker_id=NULL,lease_until=NULL,version=version+1,updated_at=clock_timestamp(),error_code=NULL WHERE id=? AND fence=?",j.id(),j.fence());
            jobs.endAttempt(j.id());jobs.audit(j.id(),Event.COMPLETED);boundary("completion",deadline);
        });
    }
    private static String value(String field,String type){return "(s.payload->>'"+field+"')"+(type==null?"":"::"+type);}
    private static String ref(String field,String entity){return "(SELECT local_id FROM transfer_mapping WHERE job_id=s.job_id AND entity='"+entity+"' AND source_id="+value(field,"uuid")+")";}
    private void insert(UUID id,String entity,String table,String columns,String expressions) {
        jdbc.update("INSERT INTO "+table+"(id,"+columns+") SELECT m.local_id,"+expressions+" FROM transfer_stage s JOIN transfer_mapping m ON m.job_id=s.job_id AND m.entity=s.entity AND m.source_id=s.source_id WHERE s.job_id=? AND s.entity='"+entity+"' ORDER BY s.source_id",id);
    }
    private void publish(UUID id,long deadline) {
        insert(id,"users","app_user","display_name,role,account_state,created_at",value("displayName",null)+",'MEMBER','IMPORTED_INACTIVE',"+value("createdAt","timestamptz"));
        jdbc.update("""
            INSERT INTO imported_author(source_instance_id,source_user_id,local_user_id,source_email)
            SELECT coalesce((s.payload#>>'{origin,sourceInstanceId}')::uuid,(d.manifest->>'sourceInstanceId')::uuid),
                coalesce((s.payload#>>'{origin,sourceId}')::uuid,s.source_id),m.local_id,c.payload->>'email'
            FROM transfer_stage s JOIN transfer_mapping m USING(job_id,entity,source_id)
            JOIN transfer_dry_run d USING(job_id)
            LEFT JOIN transfer_stage c ON c.job_id=s.job_id AND c.entity='contacts' AND c.source_id=s.source_id
            WHERE s.job_id=? AND s.entity='users'
            """,id);boundary("users",deadline);
        insert(id,"boards","board","slug,name,description,archived,created_at",value("slug",null)+","+value("name",null)+","+value("description",null)+","+value("archived","boolean")+","+value("createdAt","timestamptz"));boundary("boards",deadline);
        insert(id,"questions","question","board_id,author_id,title,body,visibility,created_at,updated_at",ref("boardId","boards")+","+ref("authorId","users")+","+value("title",null)+","+value("body",null)+","+value("visibility",null)+","+value("createdAt","timestamptz")+","+value("updatedAt","timestamptz"));boundary("questions",deadline);
        insert(id,"replies","reply","question_id,author_id,body,visibility,created_at,updated_at",ref("questionId","questions")+","+ref("authorId","users")+","+value("body",null)+","+value("visibility",null)+","+value("createdAt","timestamptz")+","+value("updatedAt","timestamptz"));boundary("replies",deadline);
        jdbc.update("UPDATE question q SET accepted_reply_id="+ref("replyId","replies")+" FROM transfer_stage s WHERE s.job_id=? AND s.entity='acceptances' AND q.id="+ref("questionId","questions"),id);boundary("acceptances",deadline);
        insert(id,"articles","knowledge_article","slug,title,body,status,author_id,created_at,updated_at,published_at",value("slug",null)+","+value("title",null)+","+value("body",null)+","+value("status",null)+","+ref("authorId","users")+","+value("createdAt","timestamptz")+","+value("updatedAt","timestamptz")+","+value("publishedAt","timestamptz"));boundary("articles",deadline);
        insert(id,"reports","content_report","reporter_id,question_id,reply_id,reason,status,created_at,updated_at,resolver_id,resolved_at,resolution_decision,resolution_note",ref("reporterId","users")+","+ref("questionId","questions")+","+ref("replyId","replies")+","+value("reason",null)+","+value("status",null)+","+value("createdAt","timestamptz")+","+value("updatedAt","timestamptz")+","+ref("resolverId","users")+","+value("resolvedAt","timestamptz")+","+value("resolutionDecision",null)+","+value("resolutionNote",null));boundary("reports",deadline);
        insert(id,"actions","moderation_action","actor_id,question_id,reply_id,action,reason,created_at",ref("actorId","users")+","+ref("questionId","questions")+","+ref("replyId","replies")+","+value("action",null)+","+value("reason",null)+","+value("createdAt","timestamptz"));boundary("actions",deadline);
        jdbc.update("""
            INSERT INTO imported_record(job_id,entity,source_id,local_id,source_instance_id,origin_source_id)
            SELECT s.job_id,s.entity,s.source_id,m.local_id,
                coalesce((s.payload#>>'{origin,sourceInstanceId}')::uuid,(d.manifest->>'sourceInstanceId')::uuid),
                coalesce((s.payload#>>'{origin,sourceId}')::uuid,s.source_id)
            FROM transfer_stage s JOIN transfer_mapping m USING(job_id,entity,source_id) JOIN transfer_dry_run d USING(job_id)
            WHERE s.job_id=?
            """,id);boundary("provenance",deadline);
    }
    public boolean runOnce() {
        if(!enabled || stores.getIfAvailable()==null)return false;
        var claimed=claim();if(claimed.isEmpty())return false;var j=claimed.get();
        try {activate(j.lease());LOG.atInfo().addKeyValue("event","import.activated").addKeyValue("jobId",j.id()).log("Native import committed");}
        catch(RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.activation_failed",e,j.id());
            // An uncertain commit is resolved by the atomic job + completion marker, never reinserted.
            jobs.locked(()->{var now=jobs.job(j.id());if(now.state()==State.COMMITTING && now.fence()==j.fence())
                jobs.fail(now,jobs.permitted(now)?Failure.WORK_FAILED:Failure.AUTHORIZATION_REVOKED);return null;});
        }
        return true;
    }
    @org.springframework.beans.factory.annotation.Value("${commonbeacon.transfer.storage.enabled:false}") private boolean scheduledStorageEnabled;
    @Scheduled(initialDelay=5000,fixedDelay=5000) public void scheduledActivation() {
        if(!scheduledStorageEnabled)return;
        try{runOnce();}catch(RuntimeException e){com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.activation_worker_failed",e,null);}
    }
}
