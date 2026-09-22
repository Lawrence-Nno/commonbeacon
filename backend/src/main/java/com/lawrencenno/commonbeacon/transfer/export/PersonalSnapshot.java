package com.lawrencenno.commonbeacon.transfer.export;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import com.lawrencenno.commonbeacon.transfer.export.CompanySnapshot.*;
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

/** Dedicated requester-only personal projections in a read-only snapshot with bounded keyset pages.
 * Only metadata remains in memory; JSONL bytes go directly to private storage. */
@Service
public class PersonalSnapshot {
    private record Projection(String from,String fields,String key,String predicate) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final ArchiveCodec codec=new ArchiveCodec();
    private final JsonMapper json=JsonMapper.builder().build();
    public PersonalSnapshot(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; snapshot=new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);snapshot.setTimeout(120);
    }
    // Every projection binds only the job's authenticated requester. No company DTOs or moderation joins.
    private Projection projection(Entity entity) {
        return switch(entity) {
            case users -> new Projection("app_user", "id,display_name,created_at,email,role", "id", "id=? AND account_state='ACTIVE'");
            case boards -> new Projection("board b", "b.id,b.slug,b.name", "b.id",
                "EXISTS (SELECT 1 FROM question q WHERE q.board_id=b.id AND q.author_id=?)");
            case questions -> new Projection("question", "id,board_id,author_id,title,body,visibility,created_at,updated_at", "id", "author_id=?");
            case replies -> new Projection("reply", "id,question_id,author_id,body,visibility,created_at,updated_at", "id", "author_id=?");
            case acceptances -> new Projection("question", "id AS question_id,accepted_reply_id AS reply_id", "id", "author_id=? AND accepted_reply_id IS NOT NULL");
            case articles -> new Projection("knowledge_article", "id,slug,title,body,status,author_id,created_at,updated_at,published_at", "id", "author_id=?");
            case reports -> new Projection("content_report", "id,question_id,reply_id,reason,created_at", "id", "reporter_id=?");
            default -> throw new IllegalArgumentException("NOT_A_PERSONAL_ENTITY");
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
        if(job.kind()!=TransferJob.Kind.PERSONAL_EXPORT)throw new IllegalArgumentException("PERSONAL_JOB_REQUIRED");
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
        var entries=new ArrayList<Entry>();long totalBytes=0,totalRows=0;
        for(Entity entity:Entity.values()) {
            if(entity==Entity.contacts || entity==Entity.actions)continue;
            var projection=projection(entity);UUID last=null;long count=0,bytes=0;
            var digest=ArchiveCodec.sha256();var crc=new CRC32();
            while(true) {
                progress.check(totalRows);
                String sql="SELECT "+projection.fields()+" FROM "+projection.from()+" WHERE "+projection.predicate()
                    +(last==null?"":" AND "+projection.key()+">?")+" ORDER BY "+projection.key()+" LIMIT 32";
                var page=last==null?jdbc.query(sql,(r,n)->row(r),job.requester()):jdbc.query(sql,(r,n)->row(r),job.requester(),last);
                for(var row:page) {
                    if(++count>entity.limit || ++totalRows>40000)throw new ExportFailure(TransferJob.Failure.TRANSFER_LIMIT_EXCEEDED);
                    byte[] encoded;
                    try {encoded=codec.encodeRow(Profile.personal,entity,row);}
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
        var manifest=json.createObjectNode().put("formatVersion",1).put("profile","personal")
            .put("sourceInstanceId",instance.toString()).put("exportId",job.id().toString()).put("productVersion","0.0.1-SNAPSHOT")
            .put("snapshotStartedAt",started.toString()).put("snapshotCompletedAt",Instant.now().truncatedTo(ChronoUnit.MICROS).toString())
            .put("referencePolicy","opaque-personal-context");
        manifest.putObject("options").put("includeContacts",false).put("includeModerationHistory",false);
        var exclusions=manifest.putArray("exclusions");
        for(String value:List.of("credentials","local-authority","sessions-and-grants","transfer-bookkeeping","derived-search-vectors","attachments"))exclusions.add(value);
        exclusions.add("other-users-content-and-profiles").add("moderator-decisions-and-notes").add("company-restoration");
        manifest.putArray("warnings").add("Contains your own hidden content, unpublished articles and contact details.")
            .add("Personal portability only; opaque external references cannot restore a company community.");
        var files=manifest.putArray("files");
        for(var entry:entries)files.addObject().put("name",entry.entity().file()).put("count",entry.count())
            .put("uncompressedBytes",entry.bytes()).put("sha256",entry.hash());
        return new Dataset(codec.encodeManifest(manifest),List.copyOf(entries),totalRows);
    }
}
