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
import java.nio.channels.SeekableByteChannel;
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
@Import({PostgresTestConfiguration.class,ImportUploadIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class ImportUploadIT {
    static final String PASSWORD="import-upload-test-password";
    static final Path ROOT=root();
    static final AtomicBoolean FULL=new AtomicBoolean();
    static final AtomicReference<Runnable> DURING_WRITE=new AtomicReference<>();
    static final AtomicInteger WINDOWS=new AtomicInteger();
    static Path root(){try{return Files.createTempDirectory("commonbeacon-import-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean ArtifactStore testArtifactStore()throws IOException {
            var local=new LocalArtifactStore(ROOT,2147483648L,0);
            return new ArtifactStore() {
                public Stored write(UUID key,long limit,Writer writer)throws IOException {
                    if(FULL.get())throw new IOException("ARTIFACT_QUOTA_EXCEEDED");
                    return local.write(key,limit,out->writer.write(new FilterOutputStream(out){
                        public void write(byte[] b,int off,int len)throws IOException {out.write(b,off,len);var hook=DURING_WRITE.getAndSet(null);if(hook!=null)hook.run();}
                    }));
                }
                public Optional<Stored> inspect(UUID key)throws IOException{return local.inspect(key);}
                public InputStream open(UUID key)throws IOException{return local.open(key);}
                public SeekableByteChannel openChannel(UUID key)throws IOException{return local.openChannel(key);}
                public void delete(UUID key)throws IOException{local.delete(key);}
                public Set<UUID> keysOlderThan(Instant cutoff)throws IOException{return local.keysOlderThan(cutoff);}
            };
        }
    }
    @Autowired ImportDryRuns dryRuns;@Autowired JdbcTemplate jdbc;@Autowired TransferJobs jobs;@Autowired ArtifactStore store;
    @Autowired com.lawrencenno.commonbeacon.transfer.access.ImportUploadController controller;
    @Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;@LocalServerPort int port;
    @MockitoBean LoginRateLimiter loginLimiter;@MockitoBean(name="transferClock") Clock clock;
    UUID admin,other,member;
    @BeforeEach void setup()throws Exception {
        when(loginLimiter.allow(anyString())).thenReturn(true);FULL.set(false);DURING_WRITE.set(null);
        Instant now=Instant.parse("2026-09-22T00:00:00Z").plusSeconds(1000L*WINDOWS.incrementAndGet());when(clock.instant()).thenReturn(now);when(clock.millis()).thenReturn(now.toEpochMilli());
        jdbc.execute("TRUNCATE app_user,board CASCADE");
        for(var key:store.keysOlderThan(Instant.now().plusSeconds(1)))store.delete(key);
        admin=user("ADMINISTRATOR");other=user("ADMINISTRATOR");member=user("MEMBER");
    }
    UUID user(String role){var id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Uploader',?,?)",id,id+"@example.test",passwords.encode(PASSWORD),role);return id;}
    String path(UUID id){return "/api/v1/admin/data/imports/"+id;}
    String domain(){return jdbc.queryForObject("SELECT md5(coalesce(jsonb_agg(to_jsonb(u) ORDER BY id)::text,'')) FROM app_user u",String.class)+
        List.of("board","question","reply","knowledge_article","content_report","moderation_action").stream().map(t->jdbc.queryForObject("SELECT count(*) FROM "+t,Long.class)).toList();}
    @Test void nativeUploadIsInspectedWithoutDomainWritesOrActivation()throws Exception {
        try(var a=new Browser(admin)) {
            String before=domain();UUID key=UUID.randomUUID();String grant=a.grant("IMPORT_UPLOAD");
            var response=a.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant",grant),key);assertThat(response.statusCode()).isEqualTo(201);
            UUID id=UUID.fromString(json.readTree(response.body()).get("id").asText());
            assertThat(json.readTree(a.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant",grant),key).body()).get("id").asText()).isEqualTo(id.toString());
            byte[] archive=zip(fixture("company-full"),false);
            response=a.put(path(id)+"/archive",archive,true,"application/zip");assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("UPLOADED");
            assertThat(a.get(path(id)+"/inspection").statusCode()).isEqualTo(409);
            assertThat(a.put(path(id)+"/archive",archive,true,"application/zip").statusCode()).isEqualTo(409);
            assertThat(new ImportInspector(jobs,store).runOnce()).isTrue();assertThat(new ImportInspector(jobs,store).runOnce()).isFalse();
            response=a.get(path(id)+"/inspection");assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var report=json.readTree(response.body());assertThat(report.get("valid").asBoolean()).isTrue();assertThat(report.get("activationAvailable").asBoolean()).isFalse();
            assertThat(report.get("rowsChecked").asLong()).isPositive();assertThat(report.get("archiveSha256").asText()).isEqualTo(HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256().digest(archive)));
            assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.REVIEW_REQUIRED);
            assertThat(domain()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_mapping",Long.class)).isZero();
        }
    }
    @Test void discourseProviderIsBoundToRequestAndMediaType()throws Exception {
        try(var a=new Browser(admin)) {
            UUID key=UUID.randomUUID();String grant=a.grant("IMPORT_UPLOAD");
            var body=Map.of("formatVersion",1,"recentAuthGrant",grant,"provider","DISCOURSE");
            var response=a.post("/api/v1/admin/data/imports",body,key);assertThat(response.statusCode()).isEqualTo(201);
            UUID id=UUID.fromString(json.readTree(response.body()).path("id").asText());
            assertThat(json.readTree(response.body()).path("provider").asText()).isEqualTo("DISCOURSE");
            assertThat(a.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant",grant,"provider","NATIVE"),key).statusCode()).isEqualTo(409);
            var data=Files.readAllBytes(Path.of("src/test/resources/data-transfer/discourse-3.5.0/bundle.json"));
            assertThat(a.put(path(id)+"/archive",data,true,"application/zip").statusCode()).isEqualTo(415);
            assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.UPLOADING);
            assertThat(a.put(path(id)+"/archive",data,true,"application/json").statusCode()).isEqualTo(200);
            assertThat(new ImportInspector(jobs,store).runOnce()).isTrue();assertThat(jobs.inspection(admin,id).valid()).isTrue();
        }
    }
    @Test void uploadAndInspectionRemainOwnerAdminCsrfAndGrantScoped()throws Exception {
        try(var a=new Browser(admin);var b=new Browser(other);var m=new Browser(member);var anon=new Browser(null)) {
            assertThat(anon.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant","a".repeat(43)),UUID.randomUUID()).statusCode()).isEqualTo(401);
            assertThat(m.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant","a".repeat(43)),UUID.randomUUID()).statusCode()).isEqualTo(403);
            assertThat(a.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant",a.grant("COMPANY_EXPORT")),UUID.randomUUID()).statusCode()).isEqualTo(403);
            assertThat(a.post("/api/v1/admin/data/imports",Map.of("formatVersion",2,"recentAuthGrant","a".repeat(43)),UUID.randomUUID()).statusCode()).isEqualTo(400);
            assertThat(a.post("/api/v1/admin/data/imports",Map.of("formatVersion",1,"recentAuthGrant","a".repeat(43),"url","https://private.invalid"),UUID.randomUUID()).statusCode()).isEqualTo(400);
            UUID id=a.create();byte[] data=zip(fixture("company-empty"),true);
            assertThat(b.put(path(id)+"/archive",data,true,"application/zip").statusCode()).isEqualTo(404);
            assertThat(b.get(path(id)+"/inspection").statusCode()).isEqualTo(404);
            assertThat(a.put(path(id)+"/archive",data,false,"application/zip").statusCode()).isEqualTo(403);
            assertThat(a.put(path(id)+"/archive",data,true,"multipart/form-data").statusCode()).isEqualTo(415);
            assertThat(a.put(path(id)+"/archive",data,true,"application/zip").statusCode()).isEqualTo(200);
        }
    }
    @Test void interruptedAndExhaustedUploadsCanRestartWithoutPublishingPartialBytes()throws Exception {
        try(var a=new Browser(admin)) {
            UUID id=a.create();FULL.set(true);
            assertThat(a.put(path(id)+"/archive",new byte[]{1},true,"application/zip").statusCode()).isEqualTo(503);FULL.set(false);
            assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.UPLOADING);
            try(var socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(10000);String cookies=a.cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
                String headers="PUT "+path(id)+"/archive HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/zip\r\nContent-Length: 100\r\nCookie: "+cookies+"\r\n"+a.csrf.get("headerName").asText()+": "+a.csrf.get("token").asText()+"\r\nConnection: close\r\n\r\nx";
                socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();socket.shutdownOutput();socket.getInputStream().readAllBytes();
            }
            assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.UPLOADING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE state='AVAILABLE'",Long.class)).isZero();
            assertThat(a.put(path(id)+"/archive",zip(fixture("company-empty"),false),true,"application/zip").statusCode()).isEqualTo(200);
            new ImportInspector(jobs,store).runOnce();assertThat(jobs.inspection(admin,id).valid()).isTrue();
        }
    }
    @Test void cancellationDuringUploadDiscardsIncompleteFile()throws Exception {
        try(var a=new Browser(admin)) {
            UUID id=a.create();DURING_WRITE.set(()->{var j=jobs.status(admin,id);jobs.cancel(admin,id,j.version());});
            assertThat(a.put(path(id)+"/archive",zip(fixture("company-empty"),true),true,"application/zip").statusCode()).isNotEqualTo(200);
            assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.CANCELLED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE state<>'DELETED'",Long.class)).isZero();
        }
    }
    @Test void rejectsCorruptionAndPersonalArchivesWithBoundedSafeReports()throws Exception {
        try(var a=new Browser(admin)) {
            UUID id=a.create();assertThat(a.put(path(id)+"/archive",zip(fixture("personal"),false),true,"application/zip").statusCode()).isEqualTo(200);
            new ImportInspector(jobs,store).runOnce();assertThat(jobs.inspection(admin,id).issues()).contains("COMPANY_PROFILE_REQUIRED");
            var j=jobs.status(admin,id);jobs.cancel(admin,id,j.version());new ArtifactReconciler(jobs,store).reconcile();
            id=a.create();a.put(path(id)+"/archive",zip(fixture("company-empty"),true),true,"application/zip");
            UUID artifact=jdbc.queryForObject("SELECT id FROM transfer_artifact WHERE job_id=?",UUID.class,id);Files.write(ROOT.resolve(artifact+".blob"),new byte[]{1,2,3});
            new ImportInspector(jobs,store).runOnce();assertThat(jobs.inspection(admin,id).issues()).contains("ARCHIVE_DIGEST_MISMATCH");
        }
    }
    @Test void revokedAdministratorCannotHaveUploadInspected()throws Exception {
        try(var a=new Browser(admin)) {
            UUID id=a.create();a.put(path(id)+"/archive",zip(fixture("company-empty"),true),true,"application/zip");
            jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);
            assertThat(new ImportInspector(jobs,store).runOnce()).isFalse();
            assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,id)).isEqualTo("AUTHORIZATION_REVOKED");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(TransferJob.Provider.class)
    void chunkedUploadEnforcesActualByteLimitWithoutBufferingTheArchive(TransferJob.Provider provider)throws Exception {
        var intent=jobs.createImport(admin,UUID.randomUUID(),provider,()->{});
        var request=org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getContentType()).thenReturn(provider==TransferJob.Provider.DISCOURSE?"application/json":"application/zip");
        when(request.getSession(false)).thenReturn(org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class));
        when(request.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            long left=provider==TransferJob.Provider.DISCOURSE?8388609L:67108865L;
            public int read(){return left-->0?1:-1;}
            public int read(byte[] b,int off,int len){if(left<=0)return -1;int n=(int)Math.min(left,len);Arrays.fill(b,off,off+n,(byte)1);left-=n;return n;}
            public boolean isFinished(){return left<=0;}public boolean isReady(){return true;}
            public void setReadListener(jakarta.servlet.ReadListener listener){throw new UnsupportedOperationException();}
        });
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(admin+"@example.test","unused",List.of());
        assertThatThrownBy(()->controller.upload(authentication,request,intent.id())).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class).hasMessageContaining(provider==TransferJob.Provider.DISCOURSE?"8 MiB":"64 MiB");
        assertThat(jobs.status(admin,intent.id()).state()).isEqualTo(TransferJob.State.UPLOADING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE state<>'DELETED'",Long.class)).isZero();
    }
    @Test void abandonedUploadsExpireWithoutBeingInspected()throws Exception {
        var intent=jobs.createImport(admin,UUID.randomUUID(),()->{});var upload=jobs.beginUpload(admin,intent.id());
        jdbc.update("UPDATE transfer_job SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",intent.id());
        new ArtifactReconciler(jobs,store).reconcile();
        assertThat(jobs.status(admin,intent.id()).errorCode()).isEqualTo(TransferJob.Failure.JOB_EXPIRED);
        assertThat(jdbc.queryForObject("SELECT state FROM transfer_artifact WHERE id=?",String.class,upload.artifact())).isEqualTo("DELETED");
        assertThat(new ImportInspector(jobs,store).runOnce()).isFalse();
    }

    UUID inspected(Map<String,byte[]> files)throws Exception {
        var job=jobs.createImport(admin,UUID.randomUUID(),()->{});var upload=jobs.beginUpload(admin,job.id());
        var bytes=zip(files,false);var saved=store.write(upload.artifact(),67108864,out->out.write(bytes));
        jobs.finishUpload(upload,saved.bytes(),saved.sha256());assertThat(new ImportInspector(jobs,store).runOnce()).isTrue();return job.id();
    }
    JsonNode reviewed(UUID id) {
        var j=jobs.status(admin,id);dryRuns.request(admin,id,j.version(),UUID.randomUUID());
        assertThat(new ImportInspector(jobs,store).runOnce()).isFalse();
        assertThat(new ImportDryRunWorker(jobs,store,dryRuns).runOnce()).isTrue();return dryRuns.review(admin,id);
    }
    void emptyTarget(){jdbc.update("DELETE FROM app_user WHERE id=?",member);}
    @Test void dryRunStagesFullGraphWithoutChangingDomainAndReplaysStableMappings()throws Exception {
        emptyTarget();UUID id=inspected(fixture("company-full"));String before=domain();
        var j=jobs.status(admin,id);UUID key=UUID.randomUUID();var queued=dryRuns.request(admin,id,j.version(),key);
        assertThat(dryRuns.request(admin,id,j.version(),key).version()).isEqualTo(queued.version());
        assertThatThrownBy(()->dryRuns.request(admin,id,j.version()+1,key)).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(new ImportDryRunWorker(jobs,store,dryRuns).runOnce()).isTrue();var report=dryRuns.review(admin,id);
        assertThat(report.path("eligible").asBoolean()).as(report.toString()).isTrue();assertThat(report.path("fresh").asBoolean()).isTrue();
        assertThat(report.path("activationAvailable").asBoolean()).isTrue();assertThat(report.path("counts").path("users").asInt()).isEqualTo(4);
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.READY_TO_COMMIT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_stage WHERE job_id=?",Long.class,id)).isEqualTo(22);
        var mappings=jdbc.queryForList("SELECT entity,source_id,local_id FROM transfer_mapping WHERE job_id=? ORDER BY entity,source_id",id);
        var again=reviewed(id);assertThat(again.path("mappingRevision").asLong()).isEqualTo(2);
        assertThat(again.path("reviewDigest").asText()).isNotEqualTo(report.path("reviewDigest").asText());
        assertThat(jdbc.queryForList("SELECT entity,source_id,local_id FROM transfer_mapping WHERE job_id=? ORDER BY entity,source_id",id)).isEqualTo(mappings);
        assertThat(domain()).isEqualTo(before);
        assertThat(report.toString()).doesNotContain("Alex","example.test","resolutionNote","password");
    }
    @Test void leaseRecoveryReplaysPartialStageAndRejectsOldWorker()throws Exception {
        emptyTarget();UUID id=inspected(fixture("company-full"));var j=jobs.status(admin,id);dryRuns.request(admin,id,j.version(),UUID.randomUUID());
        var first=dryRuns.claim(UUID.randomUUID()).orElseThrow();
        var data=json.readTree(new String(fixture("company-full").get("users.jsonl"),java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElseThrow());
        var row=new com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Row(com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.Entity.users,1,data);
        dryRuns.stage(first.lease(),List.of(row));UUID local=jdbc.queryForObject("SELECT local_id FROM transfer_mapping WHERE job_id=?",UUID.class,id);
        jdbc.update("UPDATE transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(new ImportDryRunWorker(jobs,store,dryRuns).runOnce()).isTrue();
        assertThat(dryRuns.review(admin,id).path("eligible").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT local_id FROM transfer_mapping WHERE job_id=? AND entity='users' AND source_id=?",UUID.class,id,UUID.fromString(data.path("id").asText()))).isEqualTo(local);
        assertThatThrownBy(()->dryRuns.stage(first.lease(),List.of(row))).hasMessage("STALE_LEASE");
    }
    @Test void targetChangesAndMappingChangesInvalidateReview()throws Exception {
        emptyTarget();UUID id=inspected(fixture("company-full"));assertThat(reviewed(id).path("fresh").asBoolean()).isTrue();
        UUID transientUser=user("MEMBER");jdbc.update("DELETE FROM app_user WHERE id=?",transientUser);
        var stale=dryRuns.review(admin,id);assertThat(stale.path("fresh").asBoolean()).isFalse();assertThat(stale.path("eligible").asBoolean()).isFalse();
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.REVIEW_REQUIRED);
        assertThat(reviewed(id).path("fresh").asBoolean()).isTrue();
        jdbc.update("UPDATE transfer_mapping SET local_id=? WHERE job_id=? AND entity='boards'",UUID.randomUUID(),id);
        assertThat(dryRuns.review(admin,id).path("fresh").asBoolean()).isFalse();
    }
    @Test void nonBootstrapTargetAndDemoConfigurationBlockReadiness()throws Exception {
        UUID id=inspected(fixture("company-default"));var report=reviewed(id);
        assertThat(report.path("eligible").asBoolean()).isFalse();assertThat(report.path("errors").toString()).contains("TARGET_NOT_EMPTY_BOOTSTRAP");
        assertThat(report.path("warnings").toString()).contains("CONTACTS_EXCLUDED","MODERATION_HISTORY_EXCLUDED");
        assertThat(report.path("requiredAcknowledgements").toString()).contains("CONTACTS_EXCLUDED","MODERATION_HISTORY_EXCLUDED");
        assertThat(jobs.status(admin,id).state()).isEqualTo(TransferJob.State.REVIEW_REQUIRED);
        emptyTarget();assertThat(reviewed(id).path("eligible").asBoolean()).isTrue();
        assertThat(new ImportDryRuns(jobs,jdbc,true).review(admin,id).path("fresh").asBoolean()).isFalse();
    }
    @Test void invalidGraphTimestampsAndDuplicateOriginsBlockReadiness()throws Exception {
        emptyTarget();var files=fixture("company-full");
        String replies=new String(files.get("replies.jsonl"),java.nio.charset.StandardCharsets.UTF_8).replace("\"updatedAt\":\"2026-09-22T00:00:00Z\"","\"updatedAt\":\"2026-09-21T00:00:00Z\"");
        files.put("replies.jsonl",replies.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String origin="\"origin\":{\"sourceInstanceId\":\"00000000-0000-0000-0000-000000000100\",\"sourceId\":\"00000000-0000-0000-0000-000000000001\"},";
        files.put("users.jsonl",new String(files.get("users.jsonl"),java.nio.charset.StandardCharsets.UTF_8).replace("{\"id\":","{"+origin+"\"id\":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // A valid UUID reference that accepts another question's reply.
        files.put("acceptances.jsonl",new String(files.get("acceptances.jsonl"),java.nio.charset.StandardCharsets.UTF_8).replace("000000000030","000000000031").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        refreshManifest(files);UUID id=inspected(files);var report=reviewed(id);
        assertThat(report.path("eligible").asBoolean()).isFalse();assertThat(report.path("errors").toString()).contains("INVALID_TIMESTAMP_ORDER","DUPLICATE_ORIGIN","INVALID_ACCEPTANCE");
    }
    void refreshManifest(Map<String,byte[]> files) {
        var manifest=(tools.jackson.databind.node.ObjectNode)json.readTree(files.get("manifest.json"));
        for(var entry:manifest.path("files")) {
            byte[] bytes=files.get(entry.path("name").asText());var e=(tools.jackson.databind.node.ObjectNode)entry;
            e.put("uncompressedBytes",bytes.length);e.put("sha256",HexFormat.of().formatHex(com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec.sha256().digest(bytes)));
            e.put("count",new String(bytes,java.nio.charset.StandardCharsets.UTF_8).lines().count());
        }
        files.put("manifest.json",json.writeValueAsBytes(manifest));
    }
    @Test void unexpectedStagedRowsCannotBecomeReadyOnReplay()throws Exception {
        emptyTarget();UUID id=inspected(fixture("company-full"));reviewed(id);
        jdbc.update("INSERT INTO transfer_stage SELECT job_id,entity,?,line,payload,payload_sha256,byte_count FROM transfer_stage WHERE job_id=? AND entity='contacts'",UUID.randomUUID(),id);
        assertThat(dryRuns.review(admin,id).path("fresh").asBoolean()).isFalse();
        var report=reviewed(id);assertThat(report.path("eligible").asBoolean()).isFalse();
        assertThat(report.path("errors").toString()).contains("STAGING_COUNT_MISMATCH");
    }
    @Test void matchingEmailNeverMergesIdentityAndLocalIdCollisionsBlockReview()throws Exception {
        emptyTarget();var files=fixture("company-full");
        String email=json.readTree(new String(files.get("contacts.jsonl"),java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElseThrow()).path("email").asText();
        jdbc.update("UPDATE app_user SET email=? WHERE id=?",email,other);
        UUID id=inspected(files);var report=reviewed(id);
        assertThat(report.path("eligible").asBoolean()).isTrue();assertThat(report.path("identityCollisions").asLong()).isEqualTo(1);
        assertThat(report.path("requiredAcknowledgements").toString()).contains("IDENTITIES_REMAIN_SEPARATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imported_author",Long.class)).isZero();
        UUID local=jdbc.queryForObject("SELECT local_id FROM transfer_mapping WHERE job_id=? AND entity='users' ORDER BY source_id LIMIT 1",UUID.class,id);
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Collision','hash','ADMINISTRATOR')",local,local+"@example.test");
        report=reviewed(id);assertThat(report.path("eligible").asBoolean()).isFalse();assertThat(report.path("errors").toString()).contains("LOCAL_ID_COLLISION");
        jdbc.update("UPDATE transfer_stage SET payload=jsonb_set(payload,'{displayName}','\"changed\"'::jsonb) WHERE job_id=? AND entity='users'",id);
        assertThat(dryRuns.review(admin,id).path("fresh").asBoolean()).isFalse();
    }
    @Test void cancellationAndExpiryRemoveSensitiveStaging()throws Exception {
        emptyTarget();UUID id=inspected(fixture("company-full"));reviewed(id);jobs.cancel(admin,id,jobs.status(admin,id).version());jobs.releaseCleanedReservations();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_stage",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_dry_run",Long.class)).isZero();
        new ArtifactReconciler(jobs,store).reconcile();
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM transfer_job WHERE id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE job_id=? AND state<>'DELETED'",Long.class,id)).isZero();
        id=inspected(fixture("company-full"));reviewed(id);jdbc.update("UPDATE transfer_job SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);jobs.releaseCleanedReservations();
        assertThat(jobs.status(admin,id).errorCode()).isEqualTo(TransferJob.Failure.JOB_EXPIRED);assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_stage",Long.class)).isZero();
    }
    @Test void dryRunHttpIsOwnerAdminCsrfAndVersionScoped()throws Exception {
        UUID id=inspected(fixture("company-empty"));
        try(var a=new Browser(admin);var b=new Browser(other);var m=new Browser(member)) {
            var body=Map.of("expectedVersion",jobs.status(admin,id).version());
            assertThat(b.post(path(id)+"/dry-run",body,UUID.randomUUID()).statusCode()).isEqualTo(404);
            assertThat(m.get(path(id)+"/review").statusCode()).isEqualTo(403);
            assertThat(a.send("POST",path(id)+"/dry-run",json.writeValueAsBytes(body),"application/json",false,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
            assertThat(a.post(path(id)+"/dry-run",Map.of("expectedVersion",9999),UUID.randomUUID()).statusCode()).isEqualTo(409);
            assertThat(a.post(path(id)+"/dry-run",body,UUID.randomUUID()).statusCode()).isEqualTo(202);
            assertThat(a.get(path(id)+"/review").statusCode()).isEqualTo(409);
            new ImportDryRunWorker(jobs,store,dryRuns).runOnce();var response=a.get(path(id)+"/review");
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(b.get(path(id)+"/review").statusCode()).isEqualTo(404);
            jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);assertThat(a.get(path(id)+"/review").statusCode()).isEqualTo(403);
        }
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
        if(!ROOT.getFileName().toString().startsWith("commonbeacon-import-"))throw new IOException("UNEXPECTED_TEST_ROOT");
        try(var paths=Files.walk(ROOT)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
