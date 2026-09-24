package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.lawrencenno.commonbeacon.identity.LoginRateLimiter;
import com.lawrencenno.commonbeacon.offboarding.*;
import com.lawrencenno.commonbeacon.offboarding.ErasureService.Scope;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import com.lawrencenno.commonbeacon.transfer.job.ImportReconciliation;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false","commonbeacon.erasure.company-enabled=true","commonbeacon.erasure.worker-enabled=false"})
@Import({PostgresTestConfiguration.class,ErasureIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class ErasureIT {
    static final String PASSWORD="disposable-erasure-password",TOKEN="e".repeat(43);
    static final Set<String> ACKS=Set.of("IRREVERSIBLE","RETAINED_DATA","ALL_TRANSFER_FILES","BACKUP_RETENTION");
    static final Path ROOT=root();
    static Path root(){try{return Files.createTempDirectory("commonbeacon-erasure-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean ArtifactStore testStore()throws IOException{return spy(new LocalArtifactStore(ROOT,2147483648L,0));}
    }
    @Autowired ErasureService service;@Autowired ErasureAdmission admission;@Autowired JdbcTemplate jdbc;
    @Autowired ArtifactStore store;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;
    @Autowired ImportReconciliation reconciliation;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer database;
    @Autowired org.springframework.beans.factory.ObjectProvider<ArtifactStore> stores;
    @MockitoBean LoginRateLimiter limiter;@LocalServerPort int port;
    UUID admin,member,other,board,question,reply;
    @BeforeEach void setup()throws Exception {
        reset(store);when(limiter.allow(anyString())).thenReturn(true);
        jdbc.execute("TRUNCATE erasure_job,erasure_tombstone,app_user,board CASCADE");
        for(var id:store.keysOlderThan(Instant.now().plusSeconds(1)))store.delete(id);
        admin=user("ADMINISTRATOR");member=user("MEMBER");other=user("MEMBER");board=UUID.randomUUID();question=UUID.randomUUID();reply=UUID.randomUUID();
        jdbc.update("INSERT INTO board(id,slug,name,description) VALUES (?,'erasure-board','Erasure board','Disposable erasure board')",board);
        jdbc.update("INSERT INTO question(id,board_id,author_id,title,body) VALUES (?,?,?,'Retained question','Content that remains after deletion')",question,board,member);
        jdbc.update("INSERT INTO reply(id,question_id,author_id,body) VALUES (?,?,?,'Another members retained answer')",reply,question,other);
        jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?",reply,question);
    }
    UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Private name',?,?)",id,id+"@example.test",passwords.encode(PASSWORD),role);return id;}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    UUID instance(){return jdbc.queryForObject("SELECT source_instance_id FROM transfer_instance",UUID.class);}
    UUID confirm(UUID who,Scope scope) {
        var p=service.preview(who,scope);UUID id=UUID.fromString(p.path("id").asText());
        service.confirm(who,id,p.path("digest").asText(),p.path("phrase").asText(),ACKS,TOKEN,a->{});return id;
    }
    void drain(){for(int i=0;i<150 && service.active();i++)assertThat(service.runOnce()).as("worker iteration %s",i).isTrue();assertThat(service.active()).isFalse();}
    UUID artifact()throws IOException {
        UUID job=UUID.randomUUID(),key=UUID.randomUUID();
        jdbc.update("INSERT INTO transfer_job(id,requester_id,kind,state,expires_at) VALUES (?,?,'COMPANY_EXPORT','READY',clock_timestamp()+interval '1 day')",job,admin);
        var saved=store.write(key,100,out->out.write("private export".getBytes()));
        jdbc.update("INSERT INTO transfer_artifact(id,job_id,fence,purpose,state,byte_limit,byte_count,sha256) VALUES (?,?,0,'DOWNLOAD','AVAILABLE',100,?,?)",key,job,saved.bytes(),saved.sha256());
        jdbc.update("INSERT INTO transfer_completion(job_id,fence,artifact_id) VALUES (?,0,?)",job,key);return key;
    }
    @Test void accountErasureKeepsAuthorshipAcceptanceVisibilityAndOtherPeoplesText()throws Exception {
        jdbc.update("UPDATE question SET visibility='HIDDEN' WHERE id=?",question);
        jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,'Private report text'),(?,?,?,'Someone elses report')",UUID.randomUUID(),member,question,UUID.randomUUID(),other,question);
        jdbc.update("INSERT INTO moderation_action(id,actor_id,question_id,action,reason) VALUES (?,?,?,'HIDE','Private moderation note')",UUID.randomUUID(),member,question);
        jdbc.update("UPDATE content_report SET status='RESOLVED',resolver_id=?,resolved_at=clock_timestamp(),resolution_decision='DISMISS',resolution_note='Private resolution note' WHERE reporter_id=?",member,other);
        UUID file=artifact(),foreignFile=UUID.randomUUID();store.write(foreignFile,100,out->out.write(1));
        var before=jdbc.queryForList("SELECT to_jsonb(q)::text FROM question q",String.class);
        var preview=service.preview(member,Scope.ACCOUNT);assertThat(preview.path("counts").has("content_report")).isFalse();
        UUID id=confirm(member,Scope.ACCOUNT);
        assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,member)).isEqualTo("ERASED");
        assertThat(jdbc.queryForMap("SELECT email,password_hash FROM app_user WHERE id=?",member)).containsEntry("email",null).containsEntry("password_hash",null);
        assertThatThrownBy(()->jdbc.update("UPDATE question SET title='Forbidden update' WHERE id=?",question)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        drain();assertThat(store.inspect(file)).isEmpty();assertThat(store.inspect(foreignFile)).isPresent();
        assertThat(jdbc.queryForList("SELECT to_jsonb(q)::text FROM question q",String.class)).isEqualTo(before);
        assertThat(count("reply")).isEqualTo(1);assertThat(count("app_user")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reason FROM content_report WHERE reporter_id=?",String.class,member)).isEqualTo("Removed by account deletion");
        assertThat(jdbc.queryForObject("SELECT reason FROM content_report WHERE reporter_id=?",String.class,other)).isEqualTo("Someone elses report");
        assertThat(jdbc.queryForObject("SELECT resolution_note FROM content_report WHERE reporter_id=?",String.class,other)).isEqualTo("Removed by account deletion");
        assertThat(service.status(id,TOKEN).path("state").asText()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->jdbc.update("UPDATE app_user SET account_state='ACTIVE',email='reactivate@example.test',password_hash='hash' WHERE id=?",member)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void httpRequiresFreshScopeConsentAndCsrfRevokesSessionsAndKeepsReceiptPrivate()throws Exception {
        try(var browser=new Browser(member);var oldSession=new Browser(member);var anonymous=new Browser(null)) {
            var p=json.readTree(browser.post("/api/v1/erasure/previews",Map.of("scope","ACCOUNT")).body());
            var body=new HashMap<String,Object>(Map.of("digest",p.path("digest").asText(),"phrase",p.path("phrase").asText(),"acknowledgements",ACKS,"receiptToken",TOKEN,"recentAuthGrant",browser.grant("PERSONAL_EXPORT")));
            String path="/api/v1/erasure/"+p.path("id").asText()+"/confirm";
            assertThat(browser.post(path,body).statusCode()).isEqualTo(403);assertThat(service.active()).isFalse();
            body.put("recentAuthGrant",browser.grant("ACCOUNT_ERASURE"));body.put("unexpected",true);
            assertThat(browser.post(path,body).statusCode()).isEqualTo(400);body.remove("unexpected");
            assertThat(browser.send("POST",path,json.writeValueAsBytes(body),"application/json",false,Map.of()).statusCode()).isEqualTo(403);
            var accepted=browser.post(path,body);assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
            assertThat(anonymous.get("/api/v1/boards").statusCode()).isEqualTo(503);
            assertThat(oldSession.get("/api/v1/auth/me").statusCode()).isEqualTo(503);
            String receipt="/api/v1/erasure/receipts/"+p.path("id").asText();
            assertThat(anonymous.send("GET",receipt,null,null,false,Map.of("X-Erasure-Receipt","x".repeat(43))).statusCode()).isEqualTo(404);
            var result=oldSession.send("GET",receipt,null,null,false,Map.of("X-Erasure-Receipt",TOKEN));
            assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).doesNotContain("Private name",member.toString(),"digest","receipt_hash");
            assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            drain();
            // Reusing an email must not attach the erased account's old sessions to its replacement.
            jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Replacement','hash','MEMBER')",UUID.randomUUID(),member+"@example.test");
            assertThat(oldSession.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
            assertThat(anonymous.get("/api/v1/boards").statusCode()).isEqualTo(200);
        }
    }
    @Test void companyErasesInBatchesPreservesOnlyRequestingAdministratorAndLedger()throws Exception {
        UUID file=artifact();UUID extraAdmin=user("ADMINISTRATOR");
        jdbc.update("INSERT INTO reply(id,question_id,author_id,body) SELECT gen_random_uuid(),?,?,'Disposable bounded batch reply' FROM generate_series(1,405)",question,other);
        UUID id=confirm(admin,Scope.COMPANY);drain();
        for(String table:List.of("board","question","reply","knowledge_article","content_report","moderation_action","transfer_job","transfer_artifact","imported_author","imported_record"))assertThat(count(table)).as(table).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM app_user",UUID.class)).containsExactly(admin);assertThat(store.inspect(file)).isEmpty();
        assertThat(count("erasure_tombstone")).isEqualTo(1);assertThat(service.status(id,TOKEN).path("processed").asInt()).isGreaterThan(405);
        assertThatThrownBy(()->service.preview(extraAdmin,Scope.COMPANY)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        var seeder=new com.lawrencenno.commonbeacon.identity.DemoDataSeeder(jdbc,passwords,PASSWORD,transactions);seeder.run(new org.springframework.boot.DefaultApplicationArguments());assertThat(count("board")).isZero();
    }
    @Test void deletionFailureKeepsBarrierAndRetryResumesWithoutDeletingForeignFiles()throws Exception {
        UUID file=artifact();confirm(member,Scope.ACCOUNT);service.runOnce();service.runOnce();
        doThrow(new IOException("injected")).when(store).delete(file);
        assertThat(service.runOnce()).isFalse();assertThat(service.active()).isTrue();assertThat(store.inspect(file)).isPresent();
        assertThat(jdbc.queryForObject("SELECT error_code FROM erasure_job WHERE state='RUNNING'",String.class)).isEqualTo("RETRY_REQUIRED");
        doCallRealMethod().when(store).delete(file);jdbc.update("UPDATE erasure_job SET next_attempt_at=clock_timestamp()");
        drain();assertThat(store.inspect(file)).isEmpty();assertThat(service.runOnce()).isFalse();
    }
    @Test void staleGenerationOtherInstanceAndActiveDownloadsCannotBeConfirmed()throws Exception {
        var p=service.preview(member,Scope.ACCOUNT);jdbc.update("UPDATE question SET title='Changed after preview' WHERE id=?",question);
        assertThatThrownBy(()->service.confirm(member,UUID.fromString(p.path("id").asText()),p.path("digest").asText(),p.path("phrase").asText(),ACKS,TOKEN,a->{throw new AssertionError();})).hasMessageContaining("fresh preview");
        var fresh=service.preview(member,Scope.ACCOUNT);UUID instance=instance();jdbc.update("UPDATE transfer_instance SET source_instance_id=?",UUID.randomUUID());
        assertThatThrownBy(()->service.confirm(member,UUID.fromString(fresh.path("id").asText()),fresh.path("digest").asText(),fresh.path("phrase").asText(),ACKS,TOKEN,a->{})).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        jdbc.update("UPDATE transfer_instance SET source_instance_id=?",instance);
        UUID file=artifact();jdbc.update("INSERT INTO transfer_download(id,job_id,artifact_id) SELECT ?,job_id,id FROM transfer_artifact WHERE id=?",UUID.randomUUID(),file);
        assertThatThrownBy(()->confirm(member,Scope.ACCOUNT)).hasMessageContaining("active transfers");assertThat(service.active()).isFalse();
    }
    @Test void lastAdministratorIsProtectedDuringConcurrentRoleChanges()throws Exception {
        assertThatThrownBy(()->service.preview(admin,Scope.ACCOUNT)).hasMessageContaining("Another active administrator");
        UUID second=user("ADMINISTRATOR");var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=List.of(admin,second).stream().map(id->executor.submit(()->{start.await();try{jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",id);return true;}catch(org.springframework.dao.DataAccessException e){return false;}})).toList();
            start.countDown();int successes=0;for(var task:tasks)if(task.get(15,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role='ADMINISTRATOR'",Long.class)).isEqualTo(1);
        }
    }
    @Test void admittedHttpWorkPreventsConfirmationUntilDrained()throws Exception {
        assertThat(admission.enter()).isTrue();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(()->{try{confirm(member,Scope.ACCOUNT);return true;}catch(com.lawrencenno.commonbeacon.shared.ApiFailure e){return false;}});
            assertThat(future.get(5,TimeUnit.SECONDS)).isFalse();assertThat(service.active()).isFalse();
        }finally{admission.leave();}
        confirm(member,Scope.ACCOUNT);drain();
    }
    @Test void restoreLedgerQueuesOnlyMissingSameInstanceTombstonesAndSuppressesReactivation() {
        UUID id=UUID.randomUUID();var confirmed=java.sql.Timestamp.from(Instant.now());var purge=java.sql.Timestamp.from(Instant.now().plusSeconds(30*86400));
        assertThatThrownBy(()->jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'ACCOUNT',?,?)",Boolean.class,id,UUID.randomUUID(),member,confirmed,purge)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'ACCOUNT',?,?)",Boolean.class,id,instance(),member,confirmed,purge)).isTrue();
        assertThat(service.active()).isTrue();assertThatThrownBy(()->jdbc.update("UPDATE question SET title='Not admitted' WHERE id=?",question)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        drain();assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,member)).isEqualTo("ERASED");
        assertThat(jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'ACCOUNT',?,?)",Boolean.class,id,instance(),member,confirmed,purge)).isFalse();assertThat(service.active()).isFalse();
        jdbc.update("UPDATE erasure_job SET expires_at=clock_timestamp()-interval '1 second'");service.expireReceipts();assertThat(count("erasure_job")).isZero();assertThat(count("erasure_tombstone")).isEqualTo(1);
    }
    @Test void preservesHistoricalImportCountsWhileRemovingDetailedReview() {
        UUID job=UUID.randomUUID();jdbc.update("INSERT INTO transfer_job(id,requester_id,kind,state,expires_at) VALUES (?,?,'COMPANY_IMPORT','COMPLETED',clock_timestamp()+interval '1 day')",job,admin);
        jdbc.update("INSERT INTO transfer_completion(job_id,fence) VALUES (?,0)",job);
        jdbc.update("INSERT INTO transfer_activation(job_id,request_key,request_hash,review) VALUES (?,?,?,?::jsonb)",job,UUID.randomUUID(),"a".repeat(64),"{\"counts\":{\"users\":9,\"questions\":5},\"privateDetails\":\"private name\"}");
        confirm(member,Scope.ACCOUNT);drain();var result=reconciliation.report(admin,job,null);
        assertThat(result.path("counts").path("users").path("created").asInt()).isEqualTo(9);assertThat(result.path("review").isNull()).isTrue();assertThat(result.path("detailsAvailable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT review::text FROM transfer_activation WHERE job_id=?",String.class,job)).doesNotContain("private name");
    }
    @Test void companyDeletesImportedContactsAndImmutableProvenanceOnlyInAuthorizedWorker() {
        UUID imported=UUID.randomUUID(),job=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,display_name,role,account_state) VALUES (?,'Imported person','MEMBER','IMPORTED_INACTIVE')",imported);
        jdbc.update("INSERT INTO imported_author VALUES (?,?,?,'private-source@example.test')",source,UUID.randomUUID(),imported);
        jdbc.update("INSERT INTO transfer_job(id,requester_id,kind,state,expires_at) VALUES (?,?,'COMPANY_IMPORT','COMPLETED',clock_timestamp()+interval '1 day')",job,admin);
        jdbc.update("INSERT INTO imported_record VALUES (?,'users',?,?,?,?)",job,UUID.randomUUID(),imported,source,UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("DELETE FROM imported_record")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        confirm(admin,Scope.COMPANY);drain();assertThat(count("imported_record")).isZero();assertThat(count("imported_author")).isZero();assertThat(count("app_user")).isEqualTo(1);
    }
    @Test void concurrentAdministratorDeletionAndDemotionLeaveOneUsableAdministrator()throws Exception {
        UUID second=user("ADMINISTRATOR");var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion=executor.submit(()->{start.await();try{confirm(admin,Scope.ACCOUNT);return true;}catch(RuntimeException e){return false;}});
            var demotion=executor.submit(()->{start.await();try{jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",second);return true;}catch(RuntimeException e){return false;}});
            start.countDown();boolean deleted=deletion.get(15,TimeUnit.SECONDS),demoted=demotion.get(15,TimeUnit.SECONDS);
            assertThat(deleted && demoted).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role='ADMINISTRATOR' AND account_state='ACTIVE'",Long.class)).isGreaterThanOrEqualTo(1);
            if(service.active())drain();
        }
    }
    @Test void wrongConsentOrExpiredPreviewDoesNotConsumeAuthorityAndInstanceMismatchFailsClosed() {
        var p=service.preview(member,Scope.ACCOUNT);UUID id=UUID.fromString(p.path("id").asText());
        assertThatThrownBy(()->service.confirm(member,id,p.path("digest").asText(),"ERASE COMPANY "+instance(),ACKS,TOKEN,a->{throw new AssertionError("Consumed authority");})).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        jdbc.update("UPDATE erasure_job SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThatThrownBy(()->service.confirm(member,id,p.path("digest").asText(),p.path("phrase").asText(),ACKS,TOKEN,a->{throw new AssertionError();})).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        confirm(member,Scope.ACCOUNT);UUID instance=instance();jdbc.update("UPDATE transfer_instance SET source_instance_id=?",UUID.randomUUID());assertThat(service.runOnce()).isFalse();assertThat(service.active()).isTrue();
        assertThat(count("question")).isEqualTo(1);jdbc.update("UPDATE transfer_instance SET source_instance_id=?",instance);jdbc.update("UPDATE erasure_job SET next_attempt_at=clock_timestamp()");drain();
    }
    @Test void restoredCompanyLedgerKeepsBarrierUntilEveryMissingConfirmationCompletes() {
        var now=java.sql.Timestamp.from(Instant.now());var purge=java.sql.Timestamp.from(Instant.now().plusSeconds(86400));
        jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'ACCOUNT',?,?)",Boolean.class,UUID.randomUUID(),instance(),member,now,purge);
        jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'COMPANY',?,?)",Boolean.class,UUID.randomUUID(),instance(),admin,java.sql.Timestamp.from(now.toInstant().plusSeconds(1)),purge);
        assertThat(count("erasure_job")).isEqualTo(2);drain();assertThat(count("app_user")).isEqualTo(1);assertThat(count("question")).isZero();assertThat(count("erasure_tombstone")).isEqualTo(2);
    }
    @Test void companyRequiresDeploymentOptInAndCurrentAdministrator() {
        var disabled=new ErasureService(jdbc,transactions,stores,false,30,admission);
        assertThatThrownBy(()->disabled.preview(admin,Scope.COMPANY)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        assertThatThrownBy(()->service.preview(member,Scope.COMPANY)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        assertThat(count("erasure_job")).isZero();
    }
    @Test void actualOperatorScriptsExportLedgerAndQueueReplayAgainstAnOlderSnapshot()throws Exception {
        UUID erased=member;confirm(member,Scope.ACCOUNT);drain();
        for(String name:List.of("export-erasure-ledger.sql","restore-erasure-ledger.sql"))database.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(Path.of("../scripts/"+name).toAbsolutePath()),"/tmp/"+name);
        var export=database.execInContainer("sh","-c","cd /tmp && psql -X -U test -d test -v ON_ERROR_STOP=1 -f export-erasure-ledger.sql");
        assertThat(export.getExitCode()).as(export.getStderr()).isZero();
        // Simulate the old database snapshot without the later erasure, in this test container only.
        setup();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Restored private name',?,'MEMBER')",erased,erased+"@example.test",passwords.encode(PASSWORD));
        var restored=database.execInContainer("sh","-c","cd /tmp && psql -X -U test -d test -v ON_ERROR_STOP=1 -f restore-erasure-ledger.sql");
        assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();assertThat(service.active()).isTrue();drain();
        assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,erased)).isEqualTo("ERASED");
        var replay=database.execInContainer("sh","-c","cd /tmp && psql -X -U test -d test -v ON_ERROR_STOP=1 -f restore-erasure-ledger.sql");
        assertThat(replay.getExitCode()).as(replay.getStderr()).isZero();assertThat(service.active()).isFalse();
    }
    class Browser implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();JsonNode csrf;
        Browser(UUID actor)throws Exception {csrf=json.readTree(get("/api/v1/auth/csrf").body());if(actor!=null){var r=send("POST","/api/v1/auth/login",("email="+actor+"%40example.test&password="+PASSWORD).getBytes(),"application/x-www-form-urlencoded",true,Map.of());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);csrf=json.readTree(get("/api/v1/auth/csrf").body());}}
        HttpResponse<String> send(String method,String path,byte[] body,String type,boolean token,Map<String,String> headers)throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));if(type!=null)r.header("Content-Type",type);if(token)r.header(csrf.path("headerName").asText(),csrf.path("token").asText());headers.forEach(r::header);
            return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null,false,Map.of());}
        HttpResponse<String> post(String path,Object body)throws Exception{return send("POST",path,json.writeValueAsBytes(body),"application/json",true,Map.of());}
        String grant(String scope)throws Exception{var r=post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope",scope));assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body()).path("token").asText();}
        public void close(){client.close();}
    }
    @AfterAll static void cleanup()throws IOException {
        if(!ROOT.toAbsolutePath().getFileName().toString().startsWith("commonbeacon-erasure-"))throw new IOException("UNEXPECTED_TEST_ROOT");
        try(var paths=Files.walk(ROOT)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
