package com.lawrencenno.commonbeacon.transfer.export;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import com.lawrencenno.commonbeacon.transfer.job.TransferJob;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.CRC32;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.*;

/** One read-only snapshot, explicit field projections, bounded keyset pages.
 * Only metadata remains in memory; JSONL bytes go directly to private storage. */
@Service
public class CompanySnapshot {
    public record Entry(Entity entity,long offset,long bytes,long count,String hash,long crc) {}
    public record Dataset(byte[] manifest,List<Entry> entries,long rows) {}
    @FunctionalInterface public interface Progress { void check(long rows) throws IOException; }
    public static final class ExportFailure extends IOException {
        private final TransferJob.Failure code;
        public ExportFailure(TransferJob.Failure code) {super(code.name());this.code=code;}
        public TransferJob.Failure code() {return code;}
    }
    private record Projection(String from,String fields,String key,String predicate) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final ArchiveCodec codec=new ArchiveCodec();
    private final JsonMapper json=JsonMapper.builder().build();
    public CompanySnapshot(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; snapshot=new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);snapshot.setTimeout(120);
    }
    private Projection projection(Entity entity) {
        return switch(entity) {
            case users -> new Projection("app_user u LEFT JOIN imported_author i ON i.local_user_id=u.id",
                "u.id,u.display_name,u.created_at,i.source_instance_id,i.source_user_id","u.id","true");
            case boards -> new Projection("board","id,slug,name,description,archived,created_at","id","true");
            case questions -> new Projection("question","id,board_id,author_id,title,body,visibility,created_at,updated_at","id","true");
            case replies -> new Projection("reply","id,question_id,author_id,body,visibility,created_at,updated_at","id","true");
            case acceptances -> new Projection("question","id AS question_id,accepted_reply_id AS reply_id","id","accepted_reply_id IS NOT NULL");
            case articles -> new Projection("knowledge_article","id,slug,title,body,status,author_id,created_at,updated_at,published_at","id","true");
            case contacts -> new Projection("app_user u LEFT JOIN imported_author i ON i.local_user_id=u.id",
                "u.id AS user_id,coalesce(u.email,i.source_email) AS email","u.id","coalesce(u.email,i.source_email) IS NOT NULL");
            case reports -> new Projection("content_report","id,reporter_id,question_id,reply_id,reason,status,created_at,updated_at,resolver_id,resolved_at,resolution_decision,resolution_note","id","true");
            case actions -> new Projection("moderation_action","id,actor_id,question_id,reply_id,action,reason,created_at","id","true");
        };
    }
    private static String camel(String name) {
        StringBuilder result=new StringBuilder();boolean upper=false;
        for(char c:name.toCharArray()) {if(c=='_')upper=true;else {result.append(upper?Character.toUpperCase(c):c);upper=false;}}
        return result.toString();
    }
    private ObjectNode row(ResultSet rs) throws SQLException {
        var node=json.createObjectNode();UUID instance=null,source=null;
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
            String column=rs.getMetaData().getColumnLabel(i);Object value=rs.getObject(i);
            if(column.equals("source_instance_id")){instance=(UUID)value;continue;}
            if(column.equals("source_user_id")){source=(UUID)value;continue;}
            String field=camel(column);
            if(value==null)node.putNull(field);
            else if(value instanceof Boolean flag)node.put(field,flag);
            else if(value instanceof Timestamp timestamp)node.put(field,timestamp.toInstant().toString());
            else node.put(field,value.toString());
        }
        if(instance!=null)node.putObject("origin").put("sourceInstanceId",instance.toString()).put("sourceId",source.toString());
        return node;
    }
    public Dataset extract(TransferJob job,OutputStream output,Progress progress) throws IOException {
        long deadline=System.nanoTime()+120_000_000_000L;
        try {
            return snapshot.execute(status -> {
                try {return read(job,output,rows->{
                    if(System.nanoTime()>deadline)throw new ExportFailure(TransferJob.Failure.SNAPSHOT_TIMEOUT);
                    progress.check(rows);
                });}catch(IOException e){throw new UncheckedIOException(e);}
            });
        }catch(UncheckedIOException e){throw e.getCause();}
        catch(org.springframework.transaction.TransactionTimedOutException | org.springframework.dao.QueryTimeoutException e) {
            throw new ExportFailure(TransferJob.Failure.SNAPSHOT_TIMEOUT);
        }
    }
    private Dataset read(TransferJob job,OutputStream output,Progress progress) throws IOException {
        jdbc.execute("SET LOCAL statement_timeout='120s'");
        Instant started=Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID instance=jdbc.queryForObject("SELECT source_instance_id FROM transfer_instance WHERE id=1",UUID.class);
        var options=jdbc.queryForMap("SELECT include_contacts,include_moderation_history FROM transfer_job WHERE id=?",job.id());
        boolean contacts=(Boolean)options.get("include_contacts"),history=(Boolean)options.get("include_moderation_history");
        var entries=new ArrayList<Entry>();long totalBytes=0,totalRows=0;
        for(Entity entity:Entity.values()) {
            if(entity==Entity.contacts && !contacts || (entity==Entity.reports || entity==Entity.actions) && !history)continue;
            var projection=projection(entity);UUID last=null;long count=0,bytes=0;
            var digest=ArchiveCodec.sha256();var crc=new CRC32();
            while(true) {
                progress.check(totalRows);
                String sql="SELECT "+projection.fields()+" FROM "+projection.from()+" WHERE "+projection.predicate()
                    +(last==null?"":" AND "+projection.key()+">?")+" ORDER BY "+projection.key()+" LIMIT 32";
                var page=last==null?jdbc.query(sql,(r,n)->row(r)):jdbc.query(sql,(r,n)->row(r),last);
                for(var row:page) {
                    if(++count>entity.limit || ++totalRows>40000)throw new ExportFailure(TransferJob.Failure.TRANSFER_LIMIT_EXCEEDED);
                    byte[] encoded;
                    try {encoded=codec.encodeRow(Profile.company,entity,row);}
                    catch(IllegalArgumentException invalid){throw new ExportFailure(TransferJob.Failure.INVALID_SOURCE_DATA);}
                    bytes+=encoded.length;
                    if(bytes>134217728L || totalBytes+bytes>268435456L)throw new ExportFailure(TransferJob.Failure.TRANSFER_LIMIT_EXCEEDED);
                    output.write(encoded);digest.update(encoded);crc.update(encoded);
                    last=UUID.fromString(row.get(entity==Entity.contacts?"userId":entity==Entity.acceptances?"questionId":"id").asText());
                }
                if(page.size()<32)break;
            }
            entries.add(new Entry(entity,totalBytes,bytes,count,HexFormat.of().formatHex(digest.digest()),crc.getValue()));totalBytes+=bytes;
        }
        progress.check(totalRows);
        var manifest=json.createObjectNode().put("formatVersion",1).put("profile","company")
            .put("sourceInstanceId",instance.toString()).put("exportId",job.id().toString()).put("productVersion","0.0.1-SNAPSHOT")
            .put("snapshotStartedAt",started.toString()).put("snapshotCompletedAt",Instant.now().truncatedTo(ChronoUnit.MICROS).toString())
            .put("referencePolicy","internal-only");
        manifest.putObject("options").put("includeContacts",contacts).put("includeModerationHistory",history);
        var exclusions=manifest.putArray("exclusions");
        for(String value:List.of("credentials","local-authority","sessions-and-grants","transfer-bookkeeping","derived-search-vectors","attachments"))exclusions.add(value);
        if(!contacts)exclusions.add("contacts");if(!history)exclusions.add("moderation-history");
        manifest.putArray("warnings").add("Contains private hidden content and unpublished articles.")
            .add("Imported attribution and contact details do not establish account ownership.");
        var files=manifest.putArray("files");
        for(var entry:entries)files.addObject().put("name",entry.entity().file()).put("count",entry.count())
            .put("uncompressedBytes",entry.bytes()).put("sha256",entry.hash());
        return new Dataset(codec.encodeManifest(manifest),List.copyOf(entries),totalRows);
    }
}
