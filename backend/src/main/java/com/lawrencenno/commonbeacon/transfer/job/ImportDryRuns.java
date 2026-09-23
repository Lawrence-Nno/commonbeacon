package com.lawrencenno.commonbeacon.transfer.job;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lawrencenno.commonbeacon.transfer.job.TransferJob.*;

/** Private staging only. Activation separately revalidates this review under the migration gate. */
@Service
public class ImportDryRuns {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Map<String,String> TABLES=Map.of("users","app_user","boards","board",
        "questions","question","replies","reply","articles","knowledge_article",
        "reports","content_report","actions","moderation_action");
    private final TransferJobs jobs;private final JdbcTemplate jdbc;private final boolean demo;
    public ImportDryRuns(TransferJobs jobs,JdbcTemplate jdbc,@Value("${commonbeacon.demo.enabled:false}") boolean demo) {
        this.jobs=jobs;this.jdbc=jdbc;this.demo=demo;
    }
    static String hash(String value){return HexFormat.of().formatHex(ArchiveCodec.sha256().digest(value.getBytes(StandardCharsets.UTF_8)));}
    public TransferJob request(UUID actor,UUID id,long version,UUID key) {
        return jobs.locked(()->{
            jobs.recoverLocked();var job=jobs.ownedImport(actor,id);
            String hash=hash(id+":"+version);
            jdbc.update("DELETE FROM transfer_request WHERE expires_at<=clock_timestamp()");
            var prior=jdbc.queryForList("SELECT request_hash FROM transfer_request WHERE requester_id=? AND operation='DRY_RUN' AND request_key=?",String.class,actor,key);
            if(!prior.isEmpty()) {
                if(!prior.getFirst().equals(hash))throw new IllegalStateException("IDEMPOTENCY_CONFLICT");return job;
            }
            if(job.version()!=version || !Set.of(State.REVIEW_REQUIRED,State.READY_TO_COMMIT).contains(job.state()))throw new IllegalStateException("JOB_CONFLICT");
            var sources=jdbc.queryForList("SELECT a.id,a.sha256 FROM transfer_inspection i JOIN transfer_artifact a ON a.id=i.artifact_id WHERE i.job_id=? AND a.state='AVAILABLE' AND a.expires_at>clock_timestamp() AND a.sha256=i.archive_sha256",id);
            if(sources.isEmpty())throw new IllegalStateException("ARTIFACT_EXPIRED");
            var source=sources.getFirst();
            jdbc.update("""
                INSERT INTO transfer_dry_run(job_id,artifact_id,archive_sha256,status) VALUES (?,?,?,'PENDING')
                ON CONFLICT(job_id) DO UPDATE SET status='PENDING',revision=transfer_dry_run.revision+1,report=NULL
                """,id,source.get("id"),source.get("sha256"));
            // Retention is fixed at the first dry run; retries cannot extend sensitive staging indefinitely.
            jdbc.update("UPDATE transfer_job SET state='VALIDATING',attempts=0,checkpoint=0,version=version+1,updated_at=clock_timestamp(),expires_at=(SELECT expires_at FROM transfer_dry_run WHERE job_id=?) WHERE id=?",id,id);
            jdbc.update("UPDATE transfer_artifact SET expires_at=(SELECT expires_at FROM transfer_dry_run WHERE job_id=?) WHERE id=?",id,source.get("id"));
            jdbc.update("INSERT INTO transfer_request VALUES (?,'DRY_RUN',?,?,?,clock_timestamp()+interval '24 hours')",actor,key,hash,id);
            return jobs.job(id);
        });
    }
    public Optional<TransferJob> claim(UUID worker) {
        return jobs.locked(()->{
            jobs.recoverLocked();
            if(jdbc.queryForObject("SELECT count(*) FROM transfer_job WHERE lease_until>clock_timestamp() OR state='COMMITTING'",Long.class)>0)return Optional.empty();
            var pending=jdbc.query("SELECT j.* FROM transfer_job j JOIN transfer_dry_run d ON d.job_id=j.id WHERE j.state='VALIDATING' AND j.worker_id IS NULL AND d.status='PENDING' ORDER BY j.created_at,j.id LIMIT 10 FOR UPDATE OF j",TransferJobs.ROW);
            for(var j:pending) {
                if(!jobs.permitted(j)){jobs.fail(j,Failure.AUTHORIZATION_REVOKED);continue;}
                if(j.attempts()>=3){jobs.fail(j,Failure.ATTEMPTS_EXHAUSTED);continue;}
                jdbc.update("UPDATE transfer_job SET worker_id=?,lease_until=clock_timestamp()+interval '60 seconds',fence=fence+1,attempts=attempts+1,version=version+1,updated_at=clock_timestamp() WHERE id=?",worker,j.id());
                var claimed=jobs.job(j.id());jdbc.update("INSERT INTO transfer_attempt(job_id,fence,worker_id) VALUES (?,?,?)",j.id(),claimed.fence(),worker);
                jobs.audit(j.id(),Event.CLAIMED);return Optional.of(claimed);
            }
            return Optional.empty();
        });
    }
    private void current(Lease lease) {
        var j=jobs.current(lease);
        if(j==null || j.state()!=State.VALIDATING || !"PENDING".equals(jdbc.queryForObject("SELECT status FROM transfer_dry_run WHERE job_id=?",String.class,j.id())))throw new IllegalStateException("JOB_CONFLICT");
    }
    /** Replays are checked against immutable payload digests. Batch payload budget is 1 MiB / 64 rows. */
    public void stage(Lease lease,List<ArchiveFormat.Row> rows) {
        if(rows.size()>64)throw new IllegalArgumentException("BATCH_LIMIT");
        jobs.locked(()->{
            current(lease);long bytes=0;
            for(var row:rows) {
                String entity=row.entity().name(),payload=JSON.writeValueAsString(row.data());
                int size=payload.getBytes(StandardCharsets.UTF_8).length;bytes+=size;
                if(bytes>1048576 || size>262144)throw new IllegalStateException("TRANSFER_LIMIT_EXCEEDED");
                UUID source=UUID.fromString(row.data().path(row.entity()==ArchiveFormat.Entity.contacts?"userId":row.entity()==ArchiveFormat.Entity.acceptances?"questionId":"id").asText());
                String digest=hash(payload);
                jdbc.update("INSERT INTO transfer_stage VALUES (?,?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",lease.jobId(),entity,source,row.line(),payload,digest,size);
                if(!jdbc.queryForObject("SELECT payload_sha256=? AND payload=?::jsonb FROM transfer_stage WHERE job_id=? AND entity=? AND source_id=?",Boolean.class,digest,payload,lease.jobId(),entity,source))throw new IllegalStateException("STAGING_CONFLICT");
                if(TABLES.containsKey(entity)) {
                    UUID local=UUID.nameUUIDFromBytes((lease.jobId()+":"+entity+":"+source).getBytes(StandardCharsets.UTF_8));
                    jdbc.update("INSERT INTO transfer_mapping VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",lease.jobId(),entity,source,local,lease.fence());
                    if(!local.equals(jdbc.queryForObject("SELECT local_id FROM transfer_mapping WHERE job_id=? AND entity=? AND source_id=?",UUID.class,lease.jobId(),entity,source)))throw new IllegalStateException("MAPPING_CONFLICT");
                }
            }
            long count=jdbc.queryForObject("SELECT count(*) FROM transfer_stage WHERE job_id=?",Long.class,lease.jobId());
            long totalBytes=jdbc.queryForObject("SELECT coalesce(sum(byte_count),0) FROM transfer_stage WHERE job_id=?",Long.class,lease.jobId());
            if(count>40000 || totalBytes>268435456)throw new IllegalStateException("TRANSFER_LIMIT_EXCEEDED");
            jdbc.update("UPDATE transfer_job SET checkpoint=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",count,lease.jobId());return null;
        });
    }
    ObjectNode target() {
        var result=JSON.createObjectNode();result.put("generation",jdbc.queryForObject("SELECT last_value FROM transfer_target_generation",Long.class));
        boolean eligible=!demo;var counts=result.putObject("counts");
        for(var entry:new TreeMap<>(TABLES).entrySet()) {
            long count=jdbc.queryForObject("SELECT count(*) FROM "+entry.getValue(),Long.class);counts.put(entry.getKey(),count);
            if(!entry.getKey().equals("users") && count!=0)eligible=false;
        }
        long disallowed=jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role<>'ADMINISTRATOR' OR account_state<>'ACTIVE'",Long.class);
        if(disallowed!=0 || counts.path("users").asLong()>2000)eligible=false;
        long importedAuthors=jdbc.queryForObject("SELECT count(*) FROM imported_author",Long.class);
        long importedRecords=jdbc.queryForObject("SELECT count(*) FROM imported_record",Long.class);
        eligible=eligible && importedAuthors==0 && importedRecords==0;
        result.put("importedAuthors",importedAuthors);result.put("importedRecords",importedRecords);
        result.put("ineligibleAccounts",disallowed);result.put("demoSeedingEnabled",demo);result.put("eligible",eligible);
        // Include identity state as well as the sequence: a transaction may increment the sequence before committing.
        var identities=jdbc.queryForList("SELECT id,display_name,email,role,account_state,auth_revision,created_at FROM app_user ORDER BY id LIMIT 2001");
        result.put("identityDigest",hash(JSON.writeValueAsString(identities)));
        result.put("digest",hash(result.toString()));return result;
    }
    String reviewDigest(JsonNode report) {
        var copy=(ObjectNode)report.deepCopy();copy.remove("reviewDigest");
        return hash(jdbc.queryForObject("SELECT (?::jsonb)::text",String.class,copy.toString()));
    }
    String manifestDigest(UUID id) {
        String manifest=jdbc.queryForObject("SELECT manifest::text FROM transfer_dry_run WHERE job_id=?",String.class,id);
        return manifest==null?"":hash(manifest);
    }
    String stagingDigest(UUID id) {
        var digest=ArchiveCodec.sha256();
        // Fixed metadata only, at most 40,000 records. Raw body fields never accumulate here.
        jdbc.query(c->{var ps=c.prepareStatement("SELECT s.entity,s.source_id,s.payload_sha256,s.payload::text,m.local_id FROM transfer_stage s LEFT JOIN transfer_mapping m ON m.job_id=s.job_id AND m.entity=s.entity AND m.source_id=s.source_id WHERE s.job_id=? ORDER BY s.entity,s.source_id");ps.setObject(1,id);ps.setFetchSize(16);return ps;},(org.springframework.jdbc.core.RowCallbackHandler)r->{
            String payload=r.getString(4);
            digest.update((r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+hash(JSON.readTree(payload).toString())+":"+r.getString(5)+"\n").getBytes(StandardCharsets.UTF_8));
        });
        jdbc.query("SELECT entity,source_id,local_id FROM transfer_mapping WHERE job_id=? ORDER BY entity,source_id",r->{
            digest.update((r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+"\n").getBytes(StandardCharsets.UTF_8));
        },id);
        return HexFormat.of().formatHex(digest.digest());
    }
    public void finish(Lease lease,TransferJobs.Artifact source,ArchiveFormat.Result validation) {
        jobs.locked(()->{
            current(lease);UUID id=lease.jobId();
            var run=jdbc.queryForMap("SELECT * FROM transfer_dry_run WHERE job_id=?",id);
            if(!source.id().equals(run.get("artifact_id")) || !source.hash().equals(run.get("archive_sha256")))throw new IllegalStateException("STAGING_CONFLICT");
            var report=JSON.createObjectNode();report.put("jobId",id.toString());report.put("archiveDigest",source.hash());
            report.put("validationVersion",2);report.put("mappingVersion",1);report.put("mappingRevision",((Number)run.get("revision")).longValue());
            report.put("activationAvailable",false);report.put("expiresAt",((Timestamp)run.get("expires_at")).toInstant().toString());
            var counts=report.putObject("counts");var checkpoints=report.putObject("checkpoints");
            for(var entity:ArchiveFormat.Entity.values()) {
                counts.put(entity.name(),jdbc.queryForObject("SELECT count(*) FROM transfer_stage WHERE job_id=? AND entity=?",Long.class,id,entity.name()));
                checkpoints.put(entity.name(),jdbc.queryForObject("SELECT coalesce(max(line),0) FROM transfer_stage WHERE job_id=? AND entity=?",Long.class,id,entity.name()));
            }
            var issues=report.putArray("errors");validation.issues().forEach(i->issues.add(JSON.valueToTree(i)));long[] errors={validation.totalErrors()};
            java.util.function.Consumer<ArchiveFormat.Issue> error=i->{errors[0]++;if(issues.size()<1000)issues.add(JSON.valueToTree(i));};
            long stagedRows=jdbc.queryForObject("SELECT count(*) FROM transfer_stage WHERE job_id=?",Long.class,id);
            long mappedRows=jdbc.queryForObject("SELECT count(*) FROM transfer_mapping WHERE job_id=?",Long.class,id);
            long expectedMappings=0;for(String entity:TABLES.keySet())expectedMappings+=counts.path(entity).asLong();
            if(validation.valid() && (stagedRows!=validation.rows() || mappedRows!=expectedMappings))error.accept(new ArchiveFormat.Issue("archive",0,"STAGING_COUNT_MISMATCH"));
            var manifest=validation.manifest();
            if(manifest!=null) {
                report.set("profile",manifest.get("profile"));report.set("formatVersion",manifest.get("formatVersion"));report.set("sourceInstanceId",manifest.get("sourceInstanceId"));
                String instance=manifest.path("sourceInstanceId").asText();
                var origins=new HashSet<String>();
                jdbc.query(c->{var ps=c.prepareStatement("SELECT entity,line,payload::text FROM transfer_stage WHERE job_id=? ORDER BY entity,source_id");ps.setObject(1,id);ps.setFetchSize(16);return ps;},(org.springframework.jdbc.core.RowCallbackHandler)r->{
                    String entity=r.getString(1);long line=r.getLong(2);var row=JSON.readTree(r.getString(3));
                    for(String later:List.of("updatedAt","publishedAt","resolvedAt"))if(row.hasNonNull(later) && row.hasNonNull("createdAt") && Instant.parse(row.get(later).asText()).isBefore(Instant.parse(row.get("createdAt").asText())))error.accept(new ArchiveFormat.Issue(entity+".jsonl",line,"INVALID_TIMESTAMP_ORDER"));
                    if(row.hasNonNull("publishedAt") && row.hasNonNull("updatedAt") && Instant.parse(row.get("publishedAt").asText()).isAfter(Instant.parse(row.get("updatedAt").asText())))error.accept(new ArchiveFormat.Issue(entity+".jsonl",line,"INVALID_TIMESTAMP_ORDER"));
                    if(TABLES.containsKey(entity)) {
                        var origin=row.path("origin");String originKey=entity+":"+origin.path("sourceInstanceId").asText(instance)+":"+origin.path("sourceId").asText(row.path("id").asText());
                        if(!origins.add(originKey))error.accept(new ArchiveFormat.Issue(entity+".jsonl",line,"DUPLICATE_ORIGIN"));
                    }
                });
            }
            for(var entry:new TreeMap<>(TABLES).entrySet()) {
                long collisions=jdbc.queryForObject("SELECT count(*) FROM transfer_mapping m JOIN "+entry.getValue()+" t ON t.id=m.local_id WHERE m.job_id=? AND m.entity=?",Long.class,id,entry.getKey());
                if(collisions>0)error.accept(new ArchiveFormat.Issue(entry.getKey()+".jsonl",0,"LOCAL_ID_COLLISION"));
            }
            var budget=report.putObject("budget");
            budget.put("maxRecords",40000);budget.put("maxStagedBytes",268435456);budget.put("batchRows",64);budget.put("batchBytes",1048576);
            budget.put("stagedBytes",jdbc.queryForObject("SELECT coalesce(sum(byte_count),0) FROM transfer_stage WHERE job_id=?",Long.class,id));
            budget.put("productionCapacityCertified",false);
            budget.put("maxActivationBytes",ImportActivation.MAX_BYTES);
            if(budget.path("stagedBytes").asLong()>ImportActivation.MAX_BYTES)error.accept(new ArchiveFormat.Issue("archive",0,"ACTIVATION_BUDGET_EXCEEDED"));
            var target=target();report.set("target",target);report.put("targetGeneration",target.path("generation").asLong());
            if(!target.path("eligible").asBoolean())error.accept(new ArchiveFormat.Issue("target",0,"TARGET_NOT_EMPTY_BOOTSTRAP"));
            var warnings=report.putArray("warnings");var acknowledgements=report.putArray("requiredAcknowledgements");
            if(counts.path("users").asLong()>0){warnings.add("IMPORTED_AUTHORS_INACTIVE");acknowledgements.add("IMPORTED_AUTHORS_INACTIVE");}
            acknowledgements.add("PRIVATE_CONTENT");
            if(manifest!=null) {
                if(!manifest.path("options").path("includeContacts").asBoolean())warnings.add("CONTACTS_EXCLUDED");
                if(!manifest.path("options").path("includeModerationHistory").asBoolean())warnings.add("MODERATION_HISTORY_EXCLUDED");
                else if(counts.path("actions").asLong()+counts.path("reports").asLong()>0){warnings.add("IMPORTED_HISTORY_PROVENANCE");acknowledgements.add("IMPORTED_HISTORY_PROVENANCE");}
            }
            long identityCollisions=jdbc.queryForObject("SELECT count(*) FROM transfer_stage s JOIN app_user u ON (s.entity='users' AND u.id=s.source_id) OR (s.entity='contacts' AND lower(u.email)=lower(s.payload->>'email')) WHERE s.job_id=?",Long.class,id);
            report.put("identityCollisions",identityCollisions);
            if(identityCollisions>0){warnings.add("IDENTITIES_REMAIN_SEPARATE");acknowledgements.add("IDENTITIES_REMAIN_SEPARATE");}
            acknowledgements.removeAll();acknowledgements.add("PRIVATE_CONTENT");
            warnings.forEach(acknowledgements::add);
            var preview=report.putArray("mappingPreview");
            jdbc.query("SELECT entity,source_id,local_id FROM transfer_mapping WHERE job_id=? ORDER BY entity,source_id LIMIT 100",r->{var m=preview.addObject();m.put("entity",r.getString(1));m.put("sourceId",r.getString(2));m.put("localId",r.getString(3));},id);
            report.put("mappingPreviewLimit",100);report.put("stagingDigest",stagingDigest(id));report.put("totalErrors",errors[0]);report.put("fresh",true);report.put("eligible",errors[0]==0);
            report.put("manifestDigest",manifest==null?"":hash(jdbc.queryForObject("SELECT (?::jsonb)::text",String.class,manifest.toString())));
            report.put("activationAvailable",errors[0]==0);
            report.put("reviewDigest",reviewDigest(report));
            jdbc.update("UPDATE transfer_dry_run SET status='REVIEWED',validation_version=2,manifest=?::jsonb,report=?::jsonb WHERE job_id=?",manifest==null?null:manifest.toString(),report.toString(),id);
            jdbc.update("UPDATE transfer_job SET state=?,worker_id=NULL,lease_until=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=?",errors[0]==0?"READY_TO_COMMIT":"REVIEW_REQUIRED",id);
            jobs.endAttempt(id);jobs.audit(id,Event.INSPECTED);return null;
        });
    }
    public JsonNode review(UUID actor,UUID id) {
        return jobs.locked(()->{
            jobs.recoverLocked();var job=jobs.ownedImport(actor,id);
            if(job.terminal())throw new IllegalStateException("ARTIFACT_EXPIRED");
            var rows=jdbc.queryForList("SELECT report::text FROM transfer_dry_run WHERE job_id=? AND status<>'PENDING' AND expires_at>clock_timestamp()",String.class,id);
            if(rows.isEmpty() || rows.getFirst()==null)throw new IllegalStateException("JOB_CONFLICT");
            var report=(ObjectNode)JSON.readTree(rows.getFirst());
            boolean sourceLive=jdbc.queryForObject("SELECT count(*) FROM transfer_dry_run d JOIN transfer_artifact a ON a.id=d.artifact_id WHERE d.job_id=? AND a.state='AVAILABLE' AND a.sha256=d.archive_sha256 AND a.expires_at>clock_timestamp()",Long.class,id)==1;
            boolean fresh=report.path("fresh").asBoolean() && sourceLive && report.path("validationVersion").asInt()==2 && report.path("mappingVersion").asInt()==1 && report.path("target").path("digest").asText().equals(target().path("digest").asText()) && report.path("stagingDigest").asText().equals(stagingDigest(id));
            fresh=fresh && report.path("reviewDigest").asText().equals(reviewDigest(report))
                && report.path("manifestDigest").asText().equals(manifestDigest(id));
            if(!fresh) {
                report.put("fresh",false);report.put("eligible",false);report.put("activationAvailable",false);report.put("staleReason","REVIEW_INPUT_CHANGED");
                jdbc.update("UPDATE transfer_dry_run SET status='STALE',report=?::jsonb WHERE job_id=?",report.toString(),id);
                if(job.state()==State.READY_TO_COMMIT)jdbc.update("UPDATE transfer_job SET state='REVIEW_REQUIRED',version=version+1,updated_at=clock_timestamp() WHERE id=?",id);
            }
            return report;
        });
    }
}
