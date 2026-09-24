package com.lawrencenno.commonbeacon.offboarding;

import com.lawrencenno.commonbeacon.shared.*;
import com.lawrencenno.commonbeacon.transfer.access.TransferAccess;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, instance-local erasure. Database phases are bounded and transactional;
 * file deletion is idempotent outside locks. A durable barrier stays closed on failure. */
@Service
public class ErasureService {
    public enum Scope { ACCOUNT, COMPANY }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ErasureService.class);
    private static final String REMOVED="Removed by account deletion";
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectProvider<ArtifactStore> stores;
    private final boolean companyEnabled;private final int backupDays;
    @Value("${commonbeacon.erasure.worker-enabled:true}") private boolean workerEnabled=true;
    private final ErasureAdmission admission;
    public ErasureService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectProvider<ArtifactStore> stores,
            @Value("${commonbeacon.erasure.company-enabled:false}") boolean enabled,
            @Value("${commonbeacon.erasure.backup-retention-days:30}") int backupDays,ErasureAdmission admission) {
        if(backupDays<1 || backupDays>365)throw new IllegalArgumentException("Backup retention must be 1-365 days");
        this.jdbc=jdbc;this.stores=stores;this.companyEnabled=enabled;this.backupDays=backupDays;this.admission=admission;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(10);
    }
    private static ApiFailure conflict(String code,String message){return new ApiFailure(409,code,message);}
    private <T>T exclusive(Supplier<T> action) {
        return tx.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='8s'");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,MigrationGate.KEY)))
                throw conflict("ERASURE_BUSY","An operation is in progress. Refresh the preview and try again.");
            return action.get();
        });
    }
    public boolean active(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM erasure_job WHERE state IN ('RUNNING','RESTORE_QUEUED'))",Boolean.class));}
    private UUID instance(){return jdbc.queryForObject("SELECT source_instance_id FROM transfer_instance WHERE id=1",UUID.class);}
    private static String hash(String value){return HexFormat.of().formatHex(ArchiveCodec.sha256().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private TransferAccess.Actor actor(UUID id,Scope scope) {
        var found=jdbc.query("SELECT role,auth_revision FROM app_user WHERE id=? AND account_state='ACTIVE' FOR UPDATE",
            (r,n)->new TransferAccess.Actor(id,r.getString(1),r.getLong(2)),id);
        if(found.isEmpty())throw new ApiFailure(401,"UNAUTHENTICATED","Please sign in again.");
        var actor=found.getFirst();
        if(scope==Scope.COMPANY && (!companyEnabled || !actor.role().equals("ADMINISTRATOR")))
            throw new ApiFailure(403,"COMPANY_ERASURE_DISABLED","Company erasure requires an administrator and deployment-level enablement.");
        if(scope==Scope.ACCOUNT && actor.role().equals("ADMINISTRATOR")
            && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role='ADMINISTRATOR' AND account_state='ACTIVE'",Long.class)<=1)
            throw conflict("LAST_ADMINISTRATOR","Another active administrator must exist before you delete this account.");
        return actor;
    }
    private JsonNode impact(UUID actor,Scope scope) {
        var result=JSON.createObjectNode();result.put("instanceId",instance().toString());result.put("scope",scope.name());
        result.put("generation",jdbc.queryForObject("SELECT last_value FROM transfer_target_generation",Long.class));
        var counts=result.putObject("counts");
        if(scope==Scope.COMPANY) {
            for(String table:List.of("app_user","board","question","reply","knowledge_article","content_report","moderation_action","transfer_artifact"))
                counts.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));
        } else {
            counts.put("own_reports",jdbc.queryForObject("SELECT count(*) FROM content_report WHERE reporter_id=?",Long.class,actor));
            counts.put("own_moderation_notes",jdbc.queryForObject("SELECT count(*) FROM moderation_action WHERE actor_id=?",Long.class,actor));
            counts.put("own_resolution_notes",jdbc.queryForObject("SELECT count(*) FROM content_report WHERE resolver_id=?",Long.class,actor));
            counts.put("own_articles",jdbc.queryForObject("SELECT count(*) FROM knowledge_article WHERE author_id=?",Long.class,actor));
        }
        // Bind transfer changes without exposing other members' private inventory.
        result.put("transferFingerprint",hash(jdbc.queryForObject("SELECT coalesce(string_agg(id::text||':'||version::text,',' ORDER BY id),'') FROM transfer_job",String.class)));
        result.put("ownQuestions",jdbc.queryForObject("SELECT count(*) FROM question WHERE author_id=?",Long.class,actor));
        result.put("ownReplies",jdbc.queryForObject("SELECT count(*) FROM reply WHERE author_id=?",Long.class,actor));
        result.put("backupRetentionDays",backupDays);
        result.put("phrase",scope==Scope.ACCOUNT?"DELETE MY ACCOUNT":"ERASE COMPANY "+instance());
        result.putArray("acknowledgements").add("IRREVERSIBLE").add("RETAINED_DATA").add("ALL_TRANSFER_FILES").add("BACKUP_RETENTION");
        return result;
    }
    public JsonNode preview(UUID requester,Scope scope) {
        return exclusive(()->{
            if(active())throw conflict("ERASURE_BUSY","An erasure is already in progress.");actor(requester,scope);
            jdbc.update("DELETE FROM erasure_job WHERE state='PREVIEW' AND (requester_id=? OR expires_at<clock_timestamp())",requester);
            if(jdbc.queryForObject("SELECT count(*) FROM erasure_job WHERE state='PREVIEW'",Long.class)>=100)
                throw new ApiFailure(429,"RATE_LIMITED","Too many previews. Try again later.");
            var report=(tools.jackson.databind.node.ObjectNode)impact(requester,scope);String digest=hash(requester+":"+report);
            UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);
            report.put("id",id.toString()).put("digest",digest).put("expiresAt",expires.toString());
            jdbc.update("INSERT INTO erasure_job(id,requester_id,instance_id,scope,state,preview,digest,expires_at) VALUES (?,?,?,?,'PREVIEW',?::jsonb,?,?)",
                id,requester,instance(),scope.name(),report.toString(),digest,java.sql.Timestamp.from(expires));return report;
        });
    }
    public void confirm(UUID requester,UUID id,String digest,String phrase,Set<String> acknowledgements,String receipt,
            java.util.function.Consumer<TransferAccess.Actor> authorize) {
        admission.confirm(()->exclusive(()->{
            if(active())throw conflict("ERASURE_BUSY","An erasure is already in progress. Check its receipt.");
            var rows=jdbc.queryForList("SELECT * FROM erasure_job WHERE id=? AND requester_id=? AND state='PREVIEW' AND expires_at>clock_timestamp() FOR UPDATE",id,requester);
            if(rows.isEmpty())throw conflict("STALE_ERASURE_PREVIEW","Refresh the erasure preview.");
            var job=rows.getFirst();Scope scope=Scope.valueOf((String)job.get("scope"));var actor=actor(requester,scope);
            var current=impact(requester,scope);
            if(!instance().equals(job.get("instance_id")) || !digest.equals(job.get("digest")) || !digest.equals(hash(requester+":"+current)))
                throw conflict("STALE_ERASURE_PREVIEW","The instance changed. Review a fresh preview.");
            if(!current.path("phrase").asText().equals(phrase) || !Set.of("IRREVERSIBLE","RETAINED_DATA","ALL_TRANSFER_FILES","BACKUP_RETENTION").equals(acknowledgements))
                throw new ApiFailure(400,"ERASURE_ACKNOWLEDGEMENT_REQUIRED","Confirm the exact phrase and every impact acknowledgement.");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transfer_job WHERE lease_until>clock_timestamp() OR state='COMMITTING') OR EXISTS(SELECT 1 FROM transfer_download WHERE finished_at IS NULL AND expires_at>clock_timestamp())",Boolean.class))
                throw conflict("ERASURE_BUSY","Wait for active transfers and downloads to finish, then refresh the preview.");
            if(stores.getIfAvailable()==null && jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE state<>'DELETED'",Long.class)>0)
                throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Artifact storage must be available before erasure.");
            authorize.accept(actor);
            jdbc.execute("SET LOCAL commonbeacon.erasure_worker='on'");
            jdbc.update("UPDATE erasure_job SET state='RUNNING',receipt_hash=?,confirmed_at=clock_timestamp(),expires_at=clock_timestamp()+interval '30 days' WHERE id=?",hash(receipt),id);
            jdbc.update("INSERT INTO erasure_tombstone(job_id,instance_id,subject_id,scope,backup_purge_after) VALUES (?,?,?,?,clock_timestamp()+? * interval '1 day')",id,instance(),requester,scope.name(),backupDays);
            if(scope==Scope.ACCOUNT)jdbc.update("UPDATE app_user SET email=NULL,password_hash=NULL,display_name='Deleted member',role='MEMBER',account_state='ERASED' WHERE id=?",requester);
            return null;
        }));
    }
    public JsonNode status(UUID id,String receipt) {
        if(receipt==null || !receipt.matches("[A-Za-z0-9_-]{43}"))throw new ApiFailure(404,"ERASURE_NOT_FOUND","Receipt unavailable.");
        var rows=jdbc.queryForList("SELECT scope,state,phase,processed,error_code,receipt_hash,completed_at FROM erasure_job WHERE id=? AND receipt_hash IS NOT NULL AND expires_at>clock_timestamp()",id);
        if(rows.isEmpty() || !MessageDigest.isEqual(hash(receipt).getBytes(StandardCharsets.US_ASCII),rows.getFirst().get("receipt_hash").toString().getBytes(StandardCharsets.US_ASCII)))
            throw new ApiFailure(404,"ERASURE_NOT_FOUND","Receipt unavailable.");
        var row=rows.getFirst();return JSON.createObjectNode().put("id",id.toString()).put("scope",row.get("scope").toString())
            .put("state",row.get("state").toString()).put("phase",((Number)row.get("phase")).intValue())
            .put("processed",((Number)row.get("processed")).longValue()).put("retrying",row.get("error_code")!=null);
    }
    private int deleteBatch(String table,String predicate,Object... args) {
        return jdbc.update("DELETE FROM "+table+" WHERE ctid IN (SELECT ctid FROM "+table+" WHERE "+predicate+" LIMIT 200)",args);
    }
    private int updateBatch(String table,String assignments,String predicate,Object... args) {
        return jdbc.update("UPDATE "+table+" SET "+assignments+" WHERE ctid IN (SELECT ctid FROM "+table+" WHERE "+predicate+" LIMIT 200)",args);
    }
    private int databasePhase(int phase,Scope scope,UUID actor) {
        boolean company=scope==Scope.COMPANY;
        return switch(phase) {
            case 0->updateBatch("transfer_job","state='CANCELLED',version=version+1,fence=fence+1,worker_id=NULL,lease_until=NULL,reserved_bytes=0","state NOT IN ('COMPLETED','FAILED','CANCELLED')");
            case 2->deleteBatch("transfer_stage","true");
            case 3->deleteBatch("transfer_dry_run","true");
            case 4->deleteBatch("transfer_inspection","true");
            case 5->deleteBatch("transfer_mapping","true");
            case 6->deleteBatch("transfer_download","true");
            case 7->company?deleteBatch("transfer_activation","true"):updateBatch("transfer_activation","review=jsonb_build_object('counts',review->'counts','erasedDetails',true)","NOT review @> '{\"erasedDetails\":true}'::jsonb");
            case 8->company?deleteBatch("transfer_completion","true"):updateBatch("transfer_completion","artifact_id=NULL","artifact_id IS NOT NULL");
            case 9->deleteBatch("transfer_request","true");
            case 10->deleteBatch("transfer_audit","true");
            case 11->deleteBatch("transfer_attempt","true");
            case 12->deleteBatch("transfer_artifact","state='DELETED'");
            case 13->company?deleteBatch("imported_record","true"):0;
            case 14->company?deleteBatch("imported_author","true"):0;
            case 15->company?deleteBatch("transfer_job","true"):updateBatch("transfer_job","reserved_bytes=0","reserved_bytes<>0");
            case 16->company?deleteBatch("moderation_action","true"):updateBatch("moderation_action","reason='"+REMOVED+"'","actor_id=? AND reason<>'"+REMOVED+"'",actor);
            case 17->company?deleteBatch("content_report","true"):updateBatch("content_report","reason='"+REMOVED+"',version=version+1","reporter_id=? AND reason<>'"+REMOVED+"'",actor);
            case 18->company?deleteBatch("knowledge_article","true"):updateBatch("content_report","resolution_note='"+REMOVED+"',version=version+1","resolver_id=? AND resolution_note<>'"+REMOVED+"'",actor);
            case 19->company?updateBatch("question","accepted_reply_id=NULL","accepted_reply_id IS NOT NULL"):0;
            case 20->company?deleteBatch("reply","true"):0;
            case 21->company?deleteBatch("question","true"):0;
            case 22->company?deleteBatch("board","true"):0;
            case 23->company?deleteBatch("app_user","id<>?",actor):0;
            default->0;
        };
    }
    public boolean runOnce() {
        UUID[] selected={null};
        try {
            var work=exclusive(()->{
                var jobs=jdbc.queryForList("SELECT id,requester_id,instance_id,scope,phase,confirmed_at,state FROM erasure_job WHERE (state='RUNNING' OR (state='RESTORE_QUEUED' AND NOT EXISTS(SELECT 1 FROM erasure_job WHERE state='RUNNING'))) AND next_attempt_at<=clock_timestamp() ORDER BY confirmed_at,id LIMIT 1 FOR UPDATE");
                if(jobs.isEmpty())return null;var job=jobs.getFirst();UUID id=(UUID)job.get("id");selected[0]=id;
                if(!instance().equals(job.get("instance_id")))throw new IllegalStateException("ERASURE_INSTANCE_MISMATCH");
                jdbc.execute("SET LOCAL commonbeacon.erasure_worker='on'");int phase=((Number)job.get("phase")).intValue();
                if(job.get("state").equals("RESTORE_QUEUED")) {
                    jdbc.update("UPDATE erasure_job SET state='RUNNING' WHERE id=?",id);
                    if(job.get("scope").equals("ACCOUNT"))jdbc.update("UPDATE app_user SET email=NULL,password_hash=NULL,display_name='Deleted member',role='MEMBER',account_state='ERASED' WHERE id=?",job.get("requester_id"));
                }
                if(phase==1)return job;
                if(phase>=24) {jdbc.update("UPDATE erasure_job SET state='COMPLETED',completed_at=clock_timestamp(),error_code=NULL WHERE id=?",id);return job;}
                int changed=databasePhase(phase,Scope.valueOf(job.get("scope").toString()),(UUID)job.get("requester_id"));
                jdbc.update("UPDATE erasure_job SET phase=phase+?,processed=processed+?,error_code=NULL WHERE id=?",changed==0?1:0,changed,id);return job;
            });
            if(work==null)return false;
            if(((Number)work.get("phase")).intValue()==1) {
                var store=stores.getIfAvailable();var keys=jdbc.queryForList("SELECT id FROM transfer_artifact WHERE state<>'DELETED' AND EXISTS(SELECT 1 FROM erasure_job WHERE id=? AND state='RUNNING' AND phase=1) ORDER BY id LIMIT 1",UUID.class,selected[0]);
                UUID key=keys.isEmpty()?null:keys.getFirst();
                if(store==null && key!=null)throw new IllegalStateException("ARTIFACT_STORAGE_REQUIRED");
                if(key!=null)store.delete(key);UUID deleted=key;
                exclusive(()->{
                    if(!active())return null;
                    if(deleted!=null) {jdbc.update("UPDATE transfer_artifact SET state='DELETED' WHERE id=?",deleted);jdbc.update("UPDATE erasure_job SET processed=processed+1,error_code=NULL WHERE id=? AND state='RUNNING' AND phase=1",selected[0]);}
                    else jdbc.update("UPDATE erasure_job SET phase=2,error_code=NULL WHERE id=? AND state='RUNNING' AND phase=1",selected[0]);return null;
                });
            }
            return true;
        } catch(Exception e) {
            if(selected[0]!=null) {
                jdbc.update("UPDATE erasure_job SET error_code='RETRY_REQUIRED',next_attempt_at=clock_timestamp()+interval '10 seconds' WHERE id=? AND state IN ('RUNNING','RESTORE_QUEUED')",selected[0]);
                OperationalLogs.failure(LOG,"erasure.retry_required",e,selected[0]);
            }
            return false;
        }
    }
    @Scheduled(initialDelayString="${commonbeacon.erasure.initial-delay-ms:10000}",fixedDelayString="${commonbeacon.erasure.interval-ms:1000}")
    public void scheduled() {
        // Idle polling must not acquire the exclusive migration gate and disrupt writers.
        try {if(workerEnabled && active())runOnce();}
        catch(RuntimeException e){OperationalLogs.failure(LOG,"erasure.worker_unavailable",e,null);}
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void expireReceipts() {
        jdbc.update("DELETE FROM erasure_job WHERE id IN (SELECT id FROM erasure_job WHERE state IN ('PREVIEW','COMPLETED') AND expires_at<clock_timestamp() LIMIT 200)");
    }
}
