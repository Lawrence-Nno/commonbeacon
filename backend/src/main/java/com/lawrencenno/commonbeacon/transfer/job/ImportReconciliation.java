package com.lawrencenno.commonbeacon.transfer.job;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Historical activation counts come from the atomic receipt, never mutable live rows. */
@Service
public class ImportReconciliation {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final TransferJobs jobs;private final JdbcTemplate jdbc;
    public ImportReconciliation(TransferJobs jobs,JdbcTemplate jdbc){this.jobs=jobs;this.jdbc=jdbc;}
    public JsonNode report(UUID actor,UUID id,String cursor) {
        String entity="";UUID source=new UUID(0,0);
        if(cursor!=null)try {
            if(cursor.length()>128)throw new IllegalArgumentException();
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII).split("\\|",-1);
            if(parts.length!=2 || !Set.of("users","boards","questions","replies","articles","reports","actions").contains(parts[0]))throw new IllegalArgumentException();
            entity=parts[0];source=UUID.fromString(parts[1]);
        }catch(RuntimeException e){throw new com.lawrencenno.commonbeacon.shared.ApiFailure(400,"INVALID_REQUEST","Invalid mapping cursor.");}
        String afterEntity=entity;UUID afterSource=source;
        return jobs.locked(()->{
            jobs.recoverLocked();var job=jobs.ownedImport(actor,id);if(!job.terminal())throw new IllegalStateException("JOB_CONFLICT");
            boolean complete=job.state()==TransferJob.State.COMPLETED;
            var receipts=jdbc.queryForList("SELECT review::text FROM transfer_activation WHERE job_id=?",String.class,id);
            if(!complete && receipts.isEmpty())receipts=jdbc.queryForList("SELECT report::text FROM transfer_dry_run WHERE job_id=? AND report IS NOT NULL",String.class,id);
            if(complete && receipts.isEmpty())throw new com.lawrencenno.commonbeacon.shared.ApiFailure(503,"TRANSFER_UNAVAILABLE","The activation receipt is unavailable. Retry reconciliation later.");
            if(complete && jdbc.queryForObject("SELECT count(*) FROM transfer_completion WHERE job_id=? AND fence=? AND artifact_id IS NULL",Long.class,id,job.fence())!=1)throw new IllegalStateException("JOB_CONFLICT");
            var result=JSON.createObjectNode().put("jobId",id.toString()).put("state",job.state().name()).put("detailsAvailable",!receipts.isEmpty());
            JsonNode receipt=receipts.isEmpty()?null:JSON.readTree(receipts.getFirst());
            if(receipt==null)result.putNull("review");else result.set("review",receipt);
            var counts=result.putObject("counts");
            for(var type:com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Entity.values()) {
                var c=counts.putObject(type.name());
                if(receipt==null){c.putNull("expected");c.put("created",0);c.putNull("skipped");c.putNull("rejected");}
                else {long expected=receipt.path("counts").path(type.name()).asLong();c.put("expected",expected).put("created",complete?expected:0)
                    .put("skipped",job.state()==TransferJob.State.CANCELLED?expected:0).put("rejected",job.state()==TransferJob.State.FAILED?expected:0);}
            }
            var mappings=jdbc.queryForList("SELECT entity,source_id,local_id,source_instance_id,origin_source_id FROM imported_record WHERE job_id=? AND (entity,source_id)>(?,?) ORDER BY entity,source_id LIMIT 101",id,afterEntity,afterSource);
            var page=result.putArray("mappings");
            for(var row:mappings.subList(0,Math.min(100,mappings.size())))page.addObject().put("entity",row.get("entity").toString()).put("sourceId",row.get("source_id").toString()).put("localId",row.get("local_id").toString()).put("sourceInstanceId",row.get("source_instance_id").toString()).put("originSourceId",row.get("origin_source_id").toString());
            if(mappings.size()>100){var last=mappings.get(99);result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("entity")+"|"+last.get("source_id")).getBytes(StandardCharsets.US_ASCII)));}else result.putNull("nextCursor");
            return result;
        });
    }
}
