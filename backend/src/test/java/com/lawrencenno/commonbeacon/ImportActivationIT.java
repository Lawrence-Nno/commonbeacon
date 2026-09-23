package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static com.lawrencenno.commonbeacon.transfer.inspection.QuarantineZipTest.*;
import com.lawrencenno.commonbeacon.identity.LoginRateLimiter;
import com.lawrencenno.commonbeacon.transfer.inspection.*;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false"})
@Import({PostgresTestConfiguration.class,ImportActivationIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class ImportActivationIT {
    @Autowired ImportActivation activation;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.lawrencenno.commonbeacon.transfer.export.CompanySnapshot snapshots;
    @Autowired com.lawrencenno.commonbeacon.identity.IdentityService identities;
    static final AtomicReference<java.util.function.Consumer<String>> PHASE=new AtomicReference<>();
    static final String PASSWORD="import-upload-test-password";
    static final Path ROOT=root();
    static final AtomicInteger WINDOWS=new AtomicInteger();
    static Path root(){try{return Files.createTempDirectory("commonbeacon-activation-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean ImportActivation.Probe activationProbe(){return phase->{var hook=PHASE.get();if(hook!=null)hook.accept(phase);};}
        @Bean ArtifactStore testArtifactStore()throws IOException {
            return new LocalArtifactStore(ROOT,2147483648L,0);
        }
    }
    @Autowired ImportDryRuns dryRuns;@Autowired JdbcTemplate jdbc;@Autowired TransferJobs jobs;@Autowired ArtifactStore store;
    @Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;@LocalServerPort int port;
    @MockitoBean LoginRateLimiter loginLimiter;@MockitoBean(name="transferClock") Clock clock;
    UUID admin,other;
    @BeforeEach void setup()throws Exception {
        when(loginLimiter.allow(anyString())).thenReturn(true);PHASE.set(null);
        Instant now=Instant.parse("2026-09-22T00:00:00Z").plusSeconds(1000L*WINDOWS.incrementAndGet());when(clock.instant()).thenReturn(now);when(clock.millis()).thenReturn(now.toEpochMilli());
        jdbc.execute("TRUNCATE app_user,board CASCADE");
        for(var key:store.keysOlderThan(Instant.now().plusSeconds(1)))store.delete(key);
        admin=user("ADMINISTRATOR");other=user("ADMINISTRATOR");
    }
    UUID user(String role){var id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Uploader',?,?)",id,id+"@example.test",passwords.encode(PASSWORD),role);return id;}
    String path(UUID id){return "/api/v1/admin/data/imports/"+id;}
    String domain(){return jdbc.queryForObject("SELECT md5(coalesce(jsonb_agg(to_jsonb(u) ORDER BY id)::text,'')) FROM app_user u",String.class)+
        List.of("board","question","reply","knowledge_article","content_report","moderation_action").stream().map(t->jdbc.queryForObject("SELECT count(*) FROM "+t,Long.class)).toList();}
    UUID inspected(Map<String,byte[]> files)throws Exception {
        var job=jobs.createImport(admin,UUID.randomUUID(),()->{});var upload=jobs.beginUpload(admin,job.id());
        var bytes=zip(files,true);var saved=store.write(upload.artifact(),67108864,out->out.write(bytes));
        jobs.finishUpload(upload,saved.bytes(),saved.sha256());assertThat(new ImportInspector(jobs,store).runOnce()).isTrue();return job.id();
    }
    JsonNode reviewed(UUID id) {
        var j=jobs.status(admin,id);dryRuns.request(admin,id,j.version(),UUID.randomUUID());
        assertThat(new ImportInspector(jobs,store).runOnce()).isFalse();
        assertThat(new ImportDryRunWorker(jobs,store,dryRuns).runOnce()).isTrue();return dryRuns.review(admin,id);
    }
    ImportActivation.Confirmation confirmation(UUID id,JsonNode r) {
        var warnings=new HashSet<String>();r.path("warnings").forEach(w->warnings.add(w.asText()));
        return new ImportActivation.Confirmation(jobs.status(admin,id).version(),r.path("reviewDigest").asText(),r.path("archiveDigest").asText(),r.path("targetGeneration").asLong(),true,true,warnings);
    }
    UUID confirmed()throws Exception {
        UUID id=inspected(fixture("company-full"));var r=reviewed(id);
        assertThat(r.path("eligible").asBoolean()).as(r.toString()).isTrue();
        activation.confirm(admin,id,UUID.randomUUID(),confirmation(id,r),()->{});return id;
    }
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @Test void publishesAllGroupsAndRetainsLedgerAfterCleanup()throws Exception {
        UUID id=inspected(fixture("company-full"));var r=reviewed(id);var body=confirmation(id,r);var key=UUID.randomUUID();
        var j=activation.confirm(admin,id,key,body,()->{});assertThat(j.state()).isEqualTo(TransferJob.State.COMMITTING);
        assertThatThrownBy(()->jobs.cancel(admin,id,j.version())).hasMessage("JOB_CONFLICT");
        assertThat(activation.runOnce()).isTrue();assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.COMPLETED);
        assertThat(count("app_user")).isEqualTo(6);assertThat(count("board")).isEqualTo(1);assertThat(count("question")).isEqualTo(2);
        assertThat(count("reply")).isEqualTo(3);assertThat(count("knowledge_article")).isEqualTo(3);
        assertThat(count("content_report")).isEqualTo(4);assertThat(count("moderation_action")).isEqualTo(2);
        assertThat(count("imported_record")).isEqualTo(19);assertThat(count("transfer_completion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE account_state='IMPORTED_INACTIVE' AND email IS NULL AND password_hash IS NULL AND role='MEMBER'",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE accepted_reply_id IS NOT NULL",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE search_vector IS NOT NULL",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE version<>0",Long.class)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE imported_record SET source_id=?",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var export=jobs.createCompanyExport(admin,UUID.randomUUID(),true,true,()->{});
        var bytes=new ByteArrayOutputStream();var dataset=snapshots.extract(export,bytes,n->{});var payload=bytes.toByteArray();
        var inverse=new HashMap<String,String>();jdbc.query("SELECT local_id,source_id FROM imported_record WHERE job_id=?",rs->{inverse.put(rs.getString(1),rs.getString(2));},id);
        var sourceFiles=fixture("company-full");var exportedFiles=new HashMap<String,com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.Input>();
        var sourceInstance=json.readTree(sourceFiles.get("manifest.json")).path("sourceInstanceId").asText();
        for(var entry:dataset.entries()) {
            byte[] data=Arrays.copyOfRange(payload,(int)entry.offset(),(int)(entry.offset()+entry.bytes()));exportedFiles.put(entry.entity().file(),()->new ByteArrayInputStream(data));
            var rows=new String(data,java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList();
            var expected=new HashSet<JsonNode>(new String(sourceFiles.get(entry.entity().file()),java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList());
            var actual=new HashSet<JsonNode>();
            for(var raw:rows) {
                var row=(tools.jackson.databind.node.ObjectNode)raw.deepCopy();String rowId=row.path(entry.entity().name().equals("contacts")?"userId":"id").asText();
                if(rowId.equals(admin.toString()) || rowId.equals(other.toString()))continue;
                if(!List.of("contacts","acceptances").contains(entry.entity().name())) {
                    var original=expected.stream().filter(v->v.path("id").asText().equals(inverse.get(rowId))).findFirst().orElseThrow();
                    assertThat(row.path("origin").path("sourceInstanceId").asText()).isEqualTo(original.path("origin").path("sourceInstanceId").asText(sourceInstance));
                    assertThat(row.path("origin").path("sourceId").asText()).isEqualTo(original.path("origin").path("sourceId").asText(inverse.get(rowId)));
                    if(!original.has("origin"))row.remove("origin");
                }
                for(String field:List.of("id","userId","authorId","boardId","questionId","replyId","reporterId","resolverId","actorId"))if(row.hasNonNull(field))row.put(field,inverse.get(row.path(field).asText()));
                actual.add(row);
            }
            assertThat(actual).as(entry.entity().name()).isEqualTo(expected);
        }
        assertThat(new com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec().validateCompanyImport(dataset.manifest(),exportedFiles,row->{}).valid()).isTrue();
        jobs.releaseCleanedReservations();assertThat(count("transfer_stage")).isZero();assertThat(count("imported_record")).isEqualTo(19);
        assertThat(activation.confirm(admin,id,key,body,()->{throw new AssertionError("Replay consumed grant");}).state()).isEqualTo(TransferJob.State.COMPLETED);
        assertThat(count("app_user")).isEqualTo(6);assertThat(activation.runOnce()).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"validated","users","boards","questions","replies","acceptances","articles","reports","actions","provenance","completion"})
    void everyFailureBoundaryRollsBackAllDomainAndCompletion(String phase)throws Exception {
        UUID id=confirmed();String before=domain();PHASE.set(p->{if(p.equals(phase))throw new IllegalStateException("INJECTED");});
        assertThat(activation.runOnce()).isTrue();assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.FAILED);
        assertThat(domain()).isEqualTo(before);assertThat(count("imported_record")).isZero();assertThat(count("imported_author")).isZero();assertThat(count("transfer_completion")).isZero();
    }
    @Test void rejectsStaleReviewsWarningsAndRevokedAuthority()throws Exception {
        UUID id=inspected(fixture("company-full"));var r=reviewed(id);var c=confirmation(id,r);
        var missing=new ImportActivation.Confirmation(c.expectedVersion(),c.reviewDigest(),c.archiveDigest(),c.targetGeneration(),true,true,Set.of());
        assertThatThrownBy(()->activation.confirm(admin,id,UUID.randomUUID(),missing,()->{})).hasMessage("JOB_CONFLICT");
        jdbc.update("UPDATE app_user SET display_name='Changed' WHERE id=?",other);
        assertThatThrownBy(()->activation.confirm(admin,id,UUID.randomUUID(),c,()->{})).hasMessage("JOB_CONFLICT");
        var next=confirmation(id,reviewed(id));activation.confirm(admin,id,UUID.randomUUID(),next,()->{});
        jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);
        assertThat(activation.runOnce()).isFalse();assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,id)).isEqualTo("AUTHORIZATION_REVOKED");assertThat(count("board")).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"archive","manifest","stage","target"})
    void rechecksInputsUnderExclusiveGate(String change)throws Exception {
        UUID id=confirmed();
        switch(change) {
            case "archive" -> {var file=jdbc.queryForObject("SELECT artifact_id FROM transfer_dry_run WHERE job_id=?",UUID.class,id);Files.write(ROOT.resolve(file+".blob"),new byte[]{1,2});}
            case "manifest" -> jdbc.update("UPDATE transfer_dry_run SET manifest=jsonb_set(manifest,'{sourceInstanceId}',to_jsonb(?::text)) WHERE job_id=?",UUID.randomUUID().toString(),id);
            case "stage" -> jdbc.update("UPDATE transfer_stage SET payload=jsonb_set(payload,'{displayName}','\"Tampered\"') WHERE job_id=? AND entity='users'",id);
            case "target" -> user("MEMBER");
        }
        activation.runOnce();assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.FAILED);assertThat(count("board")).isZero();assertThat(count("imported_record")).isZero();
    }
    @Test void exclusiveGateRejectsWritersWhileReadersSeeOnlyCommittedData()throws Exception {
        UUID id=confirmed();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        PHASE.set(p->{if(p.equals("questions")){entered.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Timed out");}catch(InterruptedException e){throw new RuntimeException(e);}}});
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var worker=pool.submit(()->activation.runOnce());assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(count("board")).isZero();assertThat(count("question")).isZero();
                try(var reader=new Browser(null)) {
                    var response=reader.get("/api/v1/search?q=accepted");assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(json.readTree(response.body()).path("totalElements").asLong()).isZero();assertThat(json.readTree(reader.get("/api/v1/boards").body()).size()).isZero();
                }
                assertThatThrownBy(()->identities.register(new com.lawrencenno.commonbeacon.identity.RegistrationRequest("blocked@example.test","Blocked",PASSWORD))).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class).hasMessageContaining("import");
                assertThatThrownBy(()->user("MEMBER")).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("IMPORT_IN_PROGRESS");
                assertThatThrownBy(()->jobs.cancel(admin,id,jdbc.queryForObject("SELECT version FROM transfer_job WHERE id=?",Long.class,id))).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class).hasMessageContaining("import");
                assertThatThrownBy(()->jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("IMPORT_IN_PROGRESS");
            }finally{release.countDown();}
            assertThat(worker.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.COMPLETED);assertThat(count("board")).isEqualTo(1);
        try(var reader=new Browser(null)) {
            var response=reader.get("/api/v1/search?q=accepted");assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("totalElements").asLong()).isPositive();assertThat(json.readTree(reader.get("/api/v1/boards").body()).size()).isEqualTo(1);
        }
    }
    @Test void confirmationHttpRequiresScopedGrantOwnerAndCsrfAndReplaysAfterCompletion()throws Exception {
        UUID id=inspected(fixture("company-full"));var r=reviewed(id);var c=confirmation(id,r);UUID key=UUID.randomUUID();
        try(var a=new Browser(admin);var b=new Browser(other);var anon=new Browser(null)) {
            var body=new HashMap<String,Object>();body.put("expectedVersion",c.expectedVersion());body.put("reviewDigest",c.reviewDigest());body.put("archiveDigest",c.archiveDigest());body.put("targetGeneration",c.targetGeneration());body.put("acknowledgedPrivateContent",true);body.put("acknowledgedInactiveAuthors",true);body.put("acknowledgedWarnings",c.acknowledgedWarnings());body.put("recentAuthGrant",a.grant("IMPORT_UPLOAD"));
            assertThat(anon.post(path(id)+"/confirm",body,key).statusCode()).isEqualTo(401);
            assertThat(b.post(path(id)+"/confirm",body,key).statusCode()).isEqualTo(404);
            assertThat(a.post(path(id)+"/confirm",body,key).statusCode()).isEqualTo(403);
            body.put("recentAuthGrant",a.grant("IMPORT_COMMIT"));
            assertThat(a.send("POST",path(id)+"/confirm",json.writeValueAsBytes(body),"application/json",false,Map.of("Idempotency-Key",key.toString())).statusCode()).isEqualTo(403);
            body.put("acknowledgedPrivateContent",false);assertThat(a.post(path(id)+"/confirm",body,key).statusCode()).isEqualTo(400);body.put("acknowledgedPrivateContent",true);
            var response=a.post(path(id)+"/confirm",body,key);assertThat(response.statusCode()).as(response.body()).isEqualTo(202);assertThat(response.body()).contains("COMMITTING");
            activation.runOnce();response=a.post(path(id)+"/confirm",body,key);assertThat(response.statusCode()).isEqualTo(202);assertThat(response.body()).contains("COMPLETED");
            assertThat(a.post(path(id)+"/confirm",body,UUID.randomUUID()).statusCode()).isEqualTo(409);
        }
    }
    @Test void expiredWorkerNeverReinsertsAndCompletionReconciles()throws Exception {
        UUID id=confirmed();var claimed=activation.claim().orElseThrow();
        jdbc.update("UPDATE transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(activation.runOnce()).isFalse();assertThat(jobs.status(admin,id).errorCode()).isEqualTo(TransferJob.Failure.ACTIVATION_RECONCILIATION_REQUIRED);
        assertThatThrownBy(()->activation.activate(claimed.lease())).hasMessage("STALE_LEASE");assertThat(count("board")).isZero();
    }
    @Test void priorWriterDrainsBeforeFinalEligibilityCheck()throws Exception {
        UUID id=confirmed();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s->{
                com.lawrencenno.commonbeacon.shared.MigrationGate.shared(jdbc);user("MEMBER");entered.countDown();
                try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var worker=pool.submit(()->activation.runOnce());
            try{Thread.sleep(200);assertThat(worker.isDone()).isFalse();}finally{release.countDown();}
            writer.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(worker.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.FAILED);assertThat(count("board")).isZero();assertThat(count("app_user")).isEqualTo(3);
    }
    @Test void onlyOneConfirmedImportCanOwnTheDeployment()throws Exception {
        UUID first=inspected(fixture("company-full"));var firstBody=confirmation(first,reviewed(first));UUID original=admin;
        admin=other;UUID second=inspected(fixture("company-full"));var secondBody=confirmation(second,reviewed(second));admin=original;
        activation.confirm(admin,first,UUID.randomUUID(),firstBody,()->{});
        assertThat(jobs.claimInspection(UUID.randomUUID())).isEmpty();assertThat(dryRuns.claim(UUID.randomUUID())).isEmpty();assertThat(jobs.claimCompanyExport(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(()->activation.confirm(other,second,UUID.randomUUID(),secondBody,()->{})).hasMessage("JOB_CONFLICT");
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var one=pool.submit(()->activation.claim());var two=pool.submit(()->activation.claim());
            var a=one.get(5,java.util.concurrent.TimeUnit.SECONDS);var b=two.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a.isPresent()?1:0)+(b.isPresent()?1:0)).isEqualTo(1);activation.activate(a.orElseGet(b::orElseThrow).lease());
        }
        assertThat(count("imported_record")).isEqualTo(19);assertThat(count("transfer_completion")).isEqualTo(1);
    }
    @Test void completionMarkerResolvesAnUncertainWorkerWithoutReinsertion()throws Exception {
        UUID id=confirmed();var claim=activation.claim().orElseThrow();activation.activate(claim.lease());
        jdbc.update("UPDATE transfer_job SET state='COMMITTING',worker_id=?,lease_until=clock_timestamp()-interval '1 second' WHERE id=?",claim.worker(),id);
        assertThat(activation.runOnce()).isFalse();assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.COMPLETED);assertThat(count("imported_record")).isEqualTo(19);assertThat(count("transfer_completion")).isEqualTo(1);
    }
    @Test void statementTimeoutRollsBackPublishedGroups()throws Exception {
        UUID id=confirmed();String before=domain();PHASE.set(p->{if(p.equals("questions"))jdbc.execute("SELECT pg_sleep(26)");});
        long start=System.nanoTime();activation.runOnce();assertThat((System.nanoTime()-start)/1000000).isLessThan(30000);
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.FAILED);assertThat(domain()).isEqualTo(before);assertThat(count("transfer_completion")).isZero();
    }
    private static String source(int entity,int i){return new UUID(entity,i+1L).toString();}
    Map<String,byte[]> envelope()throws Exception {
        var files=fixture("company-full");var manifest=(tools.jackson.databind.node.ObjectNode)json.readTree(files.get("manifest.json"));
        var descriptors=manifest.putArray("files");var codec=new com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec();
        int[] counts={2000,100,5000,20000,5000,1000,2000,3900,1000};int group=0;
        for(var entity:com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Entity.values()) {
            var output=new ByteArrayOutputStream();
            for(int i=0;i<counts[group];i++) {
                var row=json.createObjectNode();String timestamp="2026-09-22T00:00:00Z";
                if(!List.of("contacts","acceptances").contains(entity.name()))row.put("id",source(group,i)).put("createdAt",timestamp);
                switch(entity) {
                    case users -> row.put("displayName","Imported "+i);
                    case boards -> row.put("slug","board-"+i).put("name","Board "+i).put("description","Description").put("archived",false);
                    case questions -> row.put("boardId",source(1,i%100)).put("authorId",source(0,i%2000)).put("title","Question "+i).put("body","searchable content ".repeat(16).trim()+" "+i).put("visibility","VISIBLE").put("updatedAt",timestamp);
                    case replies -> row.put("questionId",source(2,i%5000)).put("authorId",source(0,i%2000)).put("body","searchable reply ".repeat(16).trim()+" "+i).put("visibility","VISIBLE").put("updatedAt",timestamp);
                    case acceptances -> row.put("questionId",source(2,i)).put("replyId",source(3,i));
                    case articles -> row.put("slug","article-"+i).put("title","Article "+i).put("body","searchable article ".repeat(16).trim()+" "+i).put("status","PUBLISHED").put("authorId",source(0,i%2000)).put("updatedAt",timestamp).put("publishedAt",timestamp);
                    case contacts -> row.put("userId",source(0,i)).put("email","source"+i+"@example.test");
                    case reports -> row.put("reporterId",source(0,i%2000)).put("questionId",source(2,i%5000)).putNull("replyId").put("reason","Please review this question.").put("status","OPEN").put("updatedAt",timestamp).putNull("resolverId").putNull("resolvedAt").putNull("resolutionDecision").putNull("resolutionNote");
                    case actions -> row.put("actorId",source(0,i%2000)).put("questionId",source(2,i%5000)).putNull("replyId").put("action","HIDE").put("reason","Historical source action.");
                }
                output.write(codec.encodeRow(com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Profile.company,entity,row));
            }
            byte[] data=output.toByteArray();files.put(entity.file(),data);
            descriptors.addObject().put("name",entity.file()).put("count",counts[group++]).put("uncompressedBytes",data.length).put("sha256",HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256().digest(data)));
        }
        files.put("manifest.json",codec.encodeManifest(manifest));return files;
    }
    @Test void measuredMaximumRecordEnvelopeCommitsWithinThirtySeconds()throws Exception {
        UUID id=inspected(envelope());var report=reviewed(id);assertThat(report.path("eligible").asBoolean()).as(report.toString()).isTrue();
        assertThat(count("transfer_stage")).isEqualTo(40000);long bytes=report.path("budget").path("stagedBytes").asLong();assertThat(bytes).isBetween(16000000L,ImportActivation.MAX_BYTES);
        activation.confirm(admin,id,UUID.randomUUID(),confirmation(id,report),()->{});var claim=activation.claim().orElseThrow();
        String lsn=jdbc.queryForObject("SELECT pg_current_wal_insert_lsn()::text",String.class);long start=System.nanoTime();activation.activate(claim.lease());long millis=(System.nanoTime()-start)/1000000;
        long wal=jdbc.queryForObject("SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(),?::pg_lsn)",Long.class,lsn);
        long indexes=jdbc.queryForObject("SELECT sum(pg_indexes_size(c.oid)) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('app_user','board','question','reply','knowledge_article','content_report','moderation_action','imported_author','imported_record')",Long.class);
        System.out.println("ACTIVATION_BENCHMARK rows=40000 stagedBytes="+bytes+" elapsedMs="+millis+" walBytes="+wal+" indexBytes="+indexes);
        assertThat(millis).isLessThan(30000);assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.COMPLETED);assertThat(count("app_user")).isEqualTo(2002);assertThat(count("question")).isEqualTo(5000);assertThat(count("reply")).isEqualTo(20000);
    }
    @Test void oversizedValidArchiveFailsReviewBeforeAnyActivation()throws Exception {
        var files=fixture("company-full");var out=new ByteArrayOutputStream();var codec=new com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec();
        for(int i=0;i<1000;i++) {
            var article=json.createObjectNode().put("id",source(5,i)).put("slug","large-"+i).put("title","Large article "+i).put("body","word ".repeat(3400).trim()).put("status","DRAFT").put("authorId",source(0,0)).put("createdAt","2026-09-22T00:00:00Z").put("updatedAt","2026-09-22T00:00:00Z").putNull("publishedAt");
            out.write(codec.encodeRow(com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Profile.company,com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Entity.articles,article));
        }
        byte[] data=out.toByteArray();files.put("articles.jsonl",data);var manifest=(tools.jackson.databind.node.ObjectNode)json.readTree(files.get("manifest.json"));
        for(var f:manifest.path("files"))if(f.path("name").asText().equals("articles.jsonl"))((tools.jackson.databind.node.ObjectNode)f).put("count",1000).put("uncompressedBytes",data.length).put("sha256",HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256().digest(data)));
        files.put("manifest.json",codec.encodeManifest(manifest));UUID id=inspected(files);assertThat(jobs.inspection(admin,id).valid()).isTrue();
        var report=reviewed(id);assertThat(report.path("errors").toString()).contains("ACTIVATION_BUDGET_EXCEEDED");assertThat(report.path("activationAvailable").asBoolean()).isFalse();
        assertThatThrownBy(()->activation.confirm(admin,id,UUID.randomUUID(),confirmation(id,report),()->{})).hasMessage("JOB_CONFLICT");assertThat(count("board")).isZero();assertThat(count("transfer_activation")).isZero();
    }
    @Test void rejectedManifestStillHasReadableIneligibleReview()throws Exception {
        var files=fixture("company-empty");files.put("manifest.json","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID id=inspected(files);var report=reviewed(id);assertThat(report.path("eligible").asBoolean()).isFalse();assertThat(report.path("totalErrors").asLong()).isPositive();
        assertThat(report.path("activationAvailable").asBoolean()).isFalse();assertThat(count("board")).isZero();
    }
    class Browser implements AutoCloseable {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();JsonNode csrf;
        Browser(UUID actor)throws Exception {
            csrf=json.readTree(get("/api/v1/auth/csrf").body());
            if(actor!=null){var r=send("POST","/api/v1/auth/login",("email="+actor+"%40example.test&password="+PASSWORD).getBytes(),"application/x-www-form-urlencoded",true,Map.of());assertThat(r.statusCode()).isEqualTo(200);csrf=json.readTree(get("/api/v1/auth/csrf").body());}
        }
        HttpResponse<String> send(String method,String path,byte[] body,String type,boolean token,Map<String,String> headers)throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if(type!=null)r.header("Content-Type",type);if(token)r.header(csrf.get("headerName").asText(),csrf.get("token").asText());headers.forEach(r::header);
            return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null,false,Map.of());}
        HttpResponse<String> post(String path,Object body,UUID key)throws Exception{return send("POST",path,json.writeValueAsBytes(body),"application/json",true,Map.of("Idempotency-Key",key.toString()));}
        HttpResponse<String> put(String path,byte[] body,boolean csrf,String type)throws Exception{return send("PUT",path,body,type,csrf,Map.of());}
        String grant(String scope)throws Exception{var r=post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope",scope),UUID.randomUUID());assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body()).get("token").asText();}
        UUID create()throws Exception{var r=post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant",grant("IMPORT_UPLOAD")),UUID.randomUUID());assertThat(r.statusCode()).isEqualTo(201);return UUID.fromString(json.readTree(r.body()).get("id").asText());}
        public void close(){client.close();}
    }
    @AfterAll static void cleanup()throws IOException {
        if(!ROOT.getFileName().toString().startsWith("commonbeacon-activation-"))throw new IOException("UNEXPECTED_TEST_ROOT");
        try(var paths=Files.walk(ROOT)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}

