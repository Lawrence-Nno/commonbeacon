package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.lawrencenno.commonbeacon.identity.LoginRateLimiter;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
@Import({PostgresTestConfiguration.class,TransferAccessIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class TransferAccessIT {
    static final String PASSWORD="transfer-access-test-password";
    static final Path ROOT=root();
    static final AtomicInteger WINDOWS=new AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<Runnable> DURING_READ=new java.util.concurrent.atomic.AtomicReference<>();
    static Path root(){try{return Files.createTempDirectory("commonbeacon-access-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean ArtifactStore testArtifactStore() throws IOException {
            var local=new LocalArtifactStore(ROOT,268435456L,0);
            return new ArtifactStore() {
                public Stored write(UUID key,long limit,Writer writer)throws IOException{return local.write(key,limit,writer);}
                public Optional<Stored> inspect(UUID key)throws IOException{return local.inspect(key);}
                public void delete(UUID key)throws IOException{local.delete(key);}
                public Set<UUID> keysOlderThan(Instant cutoff)throws IOException{return local.keysOlderThan(cutoff);}
                public InputStream open(UUID key)throws IOException {
                    return new FilterInputStream(local.open(key)) {
                        long read;
                        public int read(byte[] bytes,int offset,int length)throws IOException {
                            int n=super.read(bytes,offset,length);if(n>0)read+=n;
                            if(read>65536){var hook=DURING_READ.getAndSet(null);if(hook!=null)hook.run();}return n;
                        }
                    };
                }
            };
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferJobs jobs;
    @Autowired ArtifactStore store;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean(name="transferClock") Clock clock;
    @MockitoBean LoginRateLimiter loginLimiter;
    Instant now;
    UUID admin,other,member;
    String hash;
    List<Browser> browsers=new ArrayList<>();
    @BeforeEach void setup() {
        now=Instant.parse("2026-09-22T00:00:00Z").plusSeconds(2000L*WINDOWS.incrementAndGet());
        when(clock.instant()).thenAnswer(i->now);when(clock.millis()).thenAnswer(i->now.toEpochMilli());
        when(loginLimiter.allow(anyString())).thenReturn(true);
        DURING_READ.set(null);
        jdbc.execute("TRUNCATE transfer_download,transfer_audit,transfer_completion,transfer_mapping,transfer_artifact,transfer_attempt,transfer_request,transfer_job");
        hash=passwords.encode(PASSWORD);admin=user("ADMINISTRATOR");other=user("ADMINISTRATOR");member=user("MEMBER");
    }
    @AfterEach void close(){browsers.forEach(b->b.client.close());}
    @AfterAll static void cleanup() throws IOException {
        if(!ROOT.getFileName().toString().startsWith("commonbeacon-access-"))throw new IllegalStateException();
        try(var paths=Files.walk(ROOT)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Access tester',?,?)",id,id+"@example.test",hash,role);return id;}
    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf;
        Browser(UUID actor)throws Exception {browsers.add(this);token();if(actor!=null){
            var response=send("POST","/api/v1/auth/login","email="+actor+"%40example.test&password="+PASSWORD,"application/x-www-form-urlencoded",true,Map.of());assertThat(response.statusCode()).isEqualTo(200);token();}}
        void token()throws Exception{csrf=json.readTree(get("/api/v1/auth/csrf").body());}
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null,false,Map.of());}
        HttpResponse<String> post(String path,Object body)throws Exception{return send("POST",path,json.writeValueAsString(body),"application/json",true,Map.of());}
        HttpResponse<String> send(String method,String path,String body,String type,boolean withCsrf,Map<String,String> headers)throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(type!=null)request.header("Content-Type",type);
            if(withCsrf)request.header(csrf.get("headerName").asText(),csrf.get("token").asText());
            headers.forEach(request::header);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        String grant(String scope)throws Exception{var response=post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope",scope));assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).get("token").asText();}
        String ticket(String path,String grant)throws Exception {var response=post(path+"/download-ticket",Map.of("recentAuthGrant",grant));assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).get("token").asText();}
        HttpResponse<String> download(String path,String ticket)throws Exception{return send("GET",path+"/download",null,null,false,Map.of("X-Download-Ticket",ticket));}
    }
    TransferJob create(UUID actor,boolean personal){return jobs.create(actor,personal?TransferJob.Kind.PERSONAL_EXPORT:TransferJob.Kind.COMPANY_EXPORT,UUID.randomUUID(),"a".repeat(64));}
    TransferJob ready(UUID actor,boolean personal){var job=create(actor,personal);new TransferWorker(jobs,store).runExportOnce((j,out,p)->out.write("private-archive-content".getBytes()));return jobs.status(actor,job.id());}
    String path(TransferJob j){return "/api/v1/"+(j.kind()==TransferJob.Kind.PERSONAL_EXPORT?"account":"admin")+"/data/jobs/"+j.id();}
    void error(HttpResponse<String> response,int status,String code)throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain(PASSWORD,"password_hash","worker_id",ROOT.toString());
    }
    @Test void authenticationCsrfAndCurrentRolesApplyThroughHttp()throws Exception {
        var visitor=new Browser(null);error(visitor.get("/api/v1/admin/data/jobs"),401,"UNAUTHENTICATED");
        var a=new Browser(admin);var job=create(admin,false);
        error(a.send("POST","/api/v1/account/data/reauthentication",json.writeValueAsString(Map.of("password",PASSWORD,"scope","DOWNLOAD")),"application/json",false,Map.of()),403,"CSRF_INVALID");
        jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);error(a.get(path(job)),403,"FORBIDDEN");
        var m=new Browser(member);error(m.get("/api/v1/admin/data/jobs"),403,"FORBIDDEN");
        jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",member);assertThat(m.get("/api/v1/admin/data/jobs").statusCode()).isEqualTo(200);
    }
    @Test void jobViewsArePrivateScopedAndBounded()throws Exception {
        var a=new Browser(admin);var b=new Browser(other);var j=create(admin,false);
        error(b.get(path(j)),404,"JOB_NOT_FOUND");error(a.get("/api/v1/account/data/jobs/"+j.id()),404,"JOB_NOT_FOUND");
        error(a.get("/api/v1/admin/data/jobs/"+UUID.randomUUID()),404,"JOB_NOT_FOUND");
        error(a.get("/api/v1/admin/data/jobs?size=101"),400,"INVALID_REQUEST");error(a.get("/api/v1/admin/data/jobs?cursor=bad"),400,"INVALID_REQUEST");
        var response=a.get(path(j));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("worker","fence","requester","email","authorization_revision","reserved_bytes");
        assertThat(json.readTree(b.get("/api/v1/admin/data/jobs").body()).get("items")).isEmpty();
    }
    @Test void listUsesStableCursorWithoutCrossProfileResults()throws Exception {
        var a=new Browser(admin);var first=create(admin,false);jobs.cancel(admin,first.id(),first.version());jobs.releaseCleanedReservations();
        var second=create(admin,false);
        var page=json.readTree(a.get("/api/v1/admin/data/jobs?size=1").body());assertThat(page.get("items").size()).isEqualTo(1);
        var next=json.readTree(a.get("/api/v1/admin/data/jobs?size=1&cursor="+page.get("nextCursor").asText()).body());
        assertThat(next.get("items").get(0).get("id").asText()).isEqualTo(first.id().toString());
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(second.id().toString());
    }
    @Test void grantsAreSingleUseScopedAndBoundToTheirSession()throws Exception {
        var a=new Browser(admin);var sameUserOtherSession=new Browser(admin);var j=ready(admin,false);
        String wrong=a.grant("COMPANY_EXPORT");error(a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",wrong)),403,"RECENT_AUTH_REQUIRED");
        String grant=a.grant("DOWNLOAD");error(sameUserOtherSession.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",grant)),403,"RECENT_AUTH_REQUIRED");
        String ticket=a.ticket(path(j),grant);error(a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",grant)),403,"RECENT_AUTH_REQUIRED");
        var download=a.download(path(j),ticket);assertThat(download.statusCode()).isEqualTo(200);assertThat(download.body()).isEqualTo("private-archive-content");
        assertThat(download.headers().firstValue("Content-Type")).contains("application/zip");
        error(a.download(path(j),ticket),403,"RECENT_AUTH_REQUIRED");
    }
    @Test void concurrentGrantConsumptionAllowsOnlyOneTicket()throws Exception {
        var a=new Browser(admin);var j=ready(admin,false);String grant=a.grant("DOWNLOAD");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Integer> call=()->{start.await();return a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",grant)).statusCode();};
            var one=pool.submit(call);var two=pool.submit(call);start.countDown();assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,403);
        }
    }
    @Test void grantsAndTicketsExpireIndependently()throws Exception {
        var a=new Browser(admin);var j=ready(admin,false);String grant=a.grant("DOWNLOAD");now=now.plusSeconds(301);
        error(a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",grant)),403,"RECENT_AUTH_REQUIRED");
        String ticket=a.ticket(path(j),a.grant("DOWNLOAD"));now=now.plusSeconds(61);error(a.download(path(j),ticket),403,"RECENT_AUTH_REQUIRED");
    }
    @Test void logoutAndRoleAbaChangesRevokeConfirmation()throws Exception {
        var a=new Browser(admin);var j=ready(admin,false);String grant=a.grant("DOWNLOAD");
        jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",admin);
        error(a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",grant)),403,"RECENT_AUTH_REQUIRED");
        String ticket=a.ticket(path(j),a.grant("DOWNLOAD"));assertThat(a.post("/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);
        error(a.download(path(j),ticket),401,"UNAUTHENTICATED");
        var fresh=new Browser(admin);error(fresh.download(path(j),ticket),403,"RECENT_AUTH_REQUIRED");
    }
    @Test void passwordChangeRevokesExistingTickets()throws Exception {
        var a=new Browser(admin);var j=ready(admin,false);String ticket=a.ticket(path(j),a.grant("DOWNLOAD"));
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",passwords.encode("replacement-test-password"),admin);
        error(a.download(path(j),ticket),403,"RECENT_AUTH_REQUIRED");
    }
    @Test void passwordChallengesAreThrottledAndBodiesAreBounded()throws Exception {
        var a=new Browser(admin);
        for(int i=0;i<5;i++)error(a.post("/api/v1/account/data/reauthentication",Map.of("password","incorrect","scope","DOWNLOAD")),403,"INVALID_CREDENTIALS");
        var response=a.post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope","DOWNLOAD"));error(response,429,"RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).contains("900");
        error(a.post("/api/v1/account/data/reauthentication",Map.of("password","a".repeat(5000),"scope","DOWNLOAD")),413,"TRANSFER_LIMIT_EXCEEDED");
    }
    @Test void cancellationIsCsrfProtectedVersionedAndIdempotent()throws Exception {
        var a=new Browser(admin);var b=new Browser(other);var j=create(admin,false);UUID key=UUID.randomUUID();
        String body=json.writeValueAsString(Map.of("expectedVersion",j.version()));var headers=Map.of("Idempotency-Key",key.toString());
        error(b.send("POST",path(j)+"/cancel",body,"application/json",true,headers),404,"JOB_NOT_FOUND");
        error(a.send("POST",path(j)+"/cancel",body,"application/json",false,headers),403,"CSRF_INVALID");
        assertThat(a.send("POST",path(j)+"/cancel",body,"application/json",true,headers).statusCode()).isEqualTo(200);
        assertThat(a.send("POST",path(j)+"/cancel",body,"application/json",true,headers).statusCode()).isEqualTo(200);
        error(a.send("POST",path(j)+"/cancel",json.writeValueAsString(Map.of("expectedVersion",999)),"application/json",true,headers),409,"IDEMPOTENCY_CONFLICT");
    }
    @Test void personalDownloadsCannotBeUsedForAnotherOwnerOrNamespace()throws Exception {
        var m=new Browser(member);var a=new Browser(admin);var j=ready(member,true);
        error(a.get(path(j)),404,"JOB_NOT_FOUND");
        String ticket=m.ticket(path(j),m.grant("DOWNLOAD"));assertThat(m.download(path(j),ticket).statusCode()).isEqualTo(200);
        error(m.get("/api/v1/admin/data/jobs/"+j.id()),403,"FORBIDDEN");
    }
    @Test void expiredArtifactsAndLiveDownloadLeasesControlCleanup()throws Exception {
        var a=new Browser(admin);var j=ready(admin,false);var download=jobs.beginDownload(admin,j.id());
        jdbc.update("UPDATE transfer_artifact SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",download.artifact());
        new ArtifactReconciler(jobs,store).reconcile();assertThat(store.inspect(download.artifact())).isPresent();
        error(a.post(path(j)+"/download-ticket",Map.of("recentAuthGrant",a.grant("DOWNLOAD"))),410,"ARTIFACT_EXPIRED");
        jobs.finishDownload(admin,j.id(),download.lease(),false);new ArtifactReconciler(jobs,store).reconcile();assertThat(store.inspect(download.artifact())).isEmpty();
    }
    @Test void authorizationRevisionStopsQueuedAndRunningWorkAfterRoleRestoration()throws Exception {
        var j=create(admin,false);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",admin);
        assertThat(jobs.heartbeat(lease)).isFalse();assertThat(jobs.status(admin,j.id()).errorCode()).isEqualTo(TransferJob.Failure.AUTHORIZATION_REVOKED);
        var a=new Browser(admin);assertThat(a.get(path(j)).statusCode()).isEqualTo(200);assertThat(jobs.claim(UUID.randomUUID())).isEmpty();
        var queued=create(other,false);jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",other);jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",other);
        assertThat(jobs.claim(UUID.randomUUID())).isEmpty();assertThat(jobs.status(other,queued.id()).errorCode()).isEqualTo(TransferJob.Failure.AUTHORIZATION_REVOKED);
    }
    @Test void revokedRoleStopsAnInFlightDownloadWithoutAppendingAnErrorBody()throws Exception {
        var a=new Browser(admin);var j=create(admin,false);
        new TransferWorker(jobs,store).runExportOnce((job,out,progress)->out.write(new byte[196608]));
        String ticket=a.ticket(path(j),a.grant("DOWNLOAD"));
        DURING_READ.set(()->jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(j)+"/download")).header("X-Download-Ticket",ticket).GET().build();
        var response=a.client.send(request,HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);
        var received=new ByteArrayOutputStream();boolean interrupted=false;
        try(var input=response.body()) {
            byte[] buffer=new byte[4096];int n;
            try{while((n=input.read(buffer))!=-1)received.write(buffer,0,n);}catch(IOException expected){interrupted=true;}
        }
        assertThat(interrupted).isTrue();assertThat(received.size()).isLessThan(196608);
        assertThat(new String(received.toByteArray())).doesNotContain("FORBIDDEN","Download authorization");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_audit WHERE job_id=? AND event='DOWNLOAD_COMPLETED'",Integer.class,j.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_download WHERE job_id=? AND finished_at IS NULL",Integer.class,j.id())).isZero();
    }
    @Test void publicationRechecksAuthorizationEvenBeforeTheNextHeartbeat() {
        var j=create(admin,false);
        new TransferWorker(jobs,store).runExportOnce((job,out,progress)->{
            out.write(1);jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);
        });
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,j.id())).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE job_id=? AND state='AVAILABLE'",Integer.class,j.id())).isZero();
    }
}
