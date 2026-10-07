package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.*;
import com.lawrencenno.commonbeacon.board.*;
import com.lawrencenno.commonbeacon.knowledge.AdminArticleService;
import com.lawrencenno.commonbeacon.offboarding.ErasureService;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.transfer.job.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "commonbeacon.identity.verification-mode=ENFORCED","commonbeacon.demo.enabled=false",
    "commonbeacon.erasure.worker-enabled=false","commonbeacon.erasure.company-enabled=true"})
@Import(PostgresTestConfiguration.class)
@org.springframework.test.context.ActiveProfiles("local")
class AuthoritativeIdentityIT {
    @Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;
    @Autowired AdminArticleService articles;@Autowired BoardService boards;@Autowired TransferJobs jobs;
    @Autowired IdentityService identity;@Autowired ErasureService erasure;@Autowired PlatformTransactionManager transactions;
    @Autowired com.lawrencenno.commonbeacon.transfer.export.PersonalSnapshot personalSnapshots;
    @org.springframework.test.context.bean.override.mockito.MockitoBean LoginRateLimiter loginLimiter;
    @LocalServerPort int port;
    static final String PASSWORD="stage-three-disposable-password";
    UUID admin,backupAdmin,member,pending,legacy;
    List<Browser> browsers=new ArrayList<>();
    @BeforeEach void setup(){
        // Throttle behavior has dedicated tests; this suite exercises many logins from one address.
        org.mockito.Mockito.when(loginLimiter.allow(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        jdbc.execute("TRUNCATE erasure_job,erasure_tombstone,app_user,board CASCADE");
        admin=user("ADMINISTRATOR","ACTIVE",true);backupAdmin=user("ADMINISTRATOR","ACTIVE",true);
        member=user("MEMBER","ACTIVE",true);pending=user("MEMBER","PENDING_VERIFICATION",false);
        legacy=user("ADMINISTRATOR","ACTIVE",false);
    }
    @AfterEach void cleanup(){browsers.forEach(b->b.client.close());SecurityContextHolder.clearContext();}
    UUID user(String role,String state,boolean verified){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role,account_state,email_verified_at) VALUES (?,?,'Stage three account',?,?,?,CASE WHEN ? THEN clock_timestamp() ELSE NULL END)",id,id+"@example.test",passwords.encode(PASSWORD),role,state,verified);
        return id;
    }
    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf;
        Browser(UUID id)throws Exception{browsers.add(this);refresh();if(id!=null){assertThat(login(id+"@example.test").statusCode()).isEqualTo(200);refresh();}}
        void refresh()throws Exception {var response=get("/api/v1/auth/csrf");assertThat(response.statusCode()).isEqualTo(200);csrf=json.readTree(response.body());}
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
        HttpResponse<String> send(String method,String path,String body,boolean form)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(body!=null){builder.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json");builder.header(csrf.path("headerName").asText(),csrf.path("token").asText());}
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String email)throws Exception{return send("POST","/api/v1/auth/login","email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+PASSWORD,true);}
        HttpResponse<String> post(String path,Object body)throws Exception{return send("POST",path,json.writeValueAsString(body),false);}
        JsonNode grant(String scope)throws Exception{var response=post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope",scope));assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body());}
    }
    void error(HttpResponse<String> response,int status,String code)throws Exception{
        assertThat(response.statusCode()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    Authentication principal(UUID id,UserRole claimedRole){return UsernamePasswordAuthenticationToken.authenticated(
        new AccountPrincipal(id,jdbc.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Long.class,id),"unused",claimedRole,true),null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+claimedRole.name())));}

    @Test void pendingAndLegacySessionsHaveExplicitLimitedCapabilitiesAndPublicBrowsing()throws Exception{
        for(UUID id:List.of(pending,legacy)){
            var browser=new Browser(id);var response=browser.get("/api/v1/auth/me");assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body());assertThat(body.path("id").asText()).isEqualTo(id.toString());
            assertThat(body.path("capabilities").path("contribute").asBoolean()).isFalse();
            assertThat(body.path("capabilities").path("administer").asBoolean()).isFalse();
            assertThat(body.path("capabilities").path("personalData").asBoolean()).isTrue();
            assertThat(response.body()).doesNotContain("authEpoch","authRevision","password",id+"@example.test");
            assertThat(browser.get("/api/v1/boards").statusCode()).isEqualTo(200);
            for(String path:List.of("/api/v1/admin/articles","/api/v1/moderation/summary","/api/v1/moderation/reports","/api/v1/admin/data/jobs"))error(browser.get(path),403,"EMAIL_VERIFICATION_REQUIRED");
            error(browser.post("/api/v1/reports",Map.of("questionId",UUID.randomUUID(),"reason","Testing limited access")),403,"EMAIL_VERIFICATION_REQUIRED");
            assertThat(browser.post("/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);
        }
    }
    @Test void verifiedAccountsRetainCurrentRolesAndCsrfControls()throws Exception{
        var browser=new Browser(admin);
        var response=browser.post("/api/v1/boards",Map.of("slug","verified-board","name","Verified board","description","A disposable verified board"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(browser.get("/api/v1/admin/articles").statusCode()).isEqualTo(200);
        assertThat(new Browser(member).get("/api/v1/admin/articles").statusCode()).isEqualTo(403);
    }
    @Test void suspensionAndCredentialChangesRevokeAllSessionsAndDenyLogin()throws Exception{
        var first=new Browser(member);var second=new Browser(member);
        jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",member);
        error(first.get("/api/v1/auth/me"),401,"UNAUTHENTICATED");error(second.get("/api/v1/account/data/jobs"),401,"UNAUTHENTICATED");
        var anonymous=new Browser(null);error(anonymous.login(member+"@example.test"),401,"INVALID_CREDENTIALS");
        jdbc.update("UPDATE app_user SET account_state='ACTIVE' WHERE id=?",member);
        var active=new Browser(member);jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",passwords.encode("replacement-disposable-password"),member);
        error(active.get("/api/v1/auth/me"),401,"UNAUTHENTICATED");
    }
    @Test void rolePromotionAndInboxProofRequireFreshLogin()throws Exception{
        var browser=new Browser(member);jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",member);
        error(browser.get("/api/v1/admin/articles"),401,"UNAUTHENTICATED");
        assertThat(new Browser(member).get("/api/v1/admin/articles").statusCode()).isEqualTo(200);
        var limited=new Browser(pending);var other=new Browser(pending);
        jdbc.update("UPDATE app_user SET account_state='ACTIVE',email_verified_at=clock_timestamp() WHERE id=?",pending);
        error(limited.get("/api/v1/auth/me"),401,"UNAUTHENTICATED");error(other.get("/api/v1/auth/me"),401,"UNAUTHENTICATED");
        assertThat(json.readTree(new Browser(pending).get("/api/v1/auth/me").body()).path("capabilities").path("contribute").asBoolean()).isTrue();
    }
    @Test void freedEmailCannotRebindOldSessionToAnotherAccount()throws Exception{
        var browser=new Browser(member);
        jdbc.update("UPDATE app_user SET email='replacement@example.test' WHERE id=?",member);
        UUID replacement=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,email_verified_at) VALUES (?,?,'Replacement account',?,clock_timestamp())",replacement,member+"@example.test",passwords.encode(PASSWORD));
        error(browser.get("/api/v1/auth/me"),401,"UNAUTHENTICATED");
        var fresh=new Browser(null);assertThat(fresh.login("replacement@example.test").statusCode()).isEqualTo(200);
        assertThat(json.readTree(fresh.get("/api/v1/auth/me").body()).path("id").asText()).isEqualTo(member.toString());
    }
    @Test void directPrivateServiceCallsRejectLimitedAndForgedCachedRoles(){
        SecurityContextHolder.getContext().setAuthentication(principal(pending,UserRole.ADMINISTRATOR));
        assertThatThrownBy(()->articles.list(null,0,20)).isInstanceOf(ApiFailure.class).extracting(e->((ApiFailure)e).code()).isEqualTo("EMAIL_VERIFICATION_REQUIRED");
        SecurityContextHolder.getContext().setAuthentication(principal(member,UserRole.ADMINISTRATOR));
        assertThatThrownBy(()->articles.list(null,0,20)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var old=principal(member,UserRole.MEMBER);jdbc.update("UPDATE app_user SET auth_epoch=auth_epoch+1 WHERE id=?",member);
        assertThatThrownBy(()->identity.current(old)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->identity.current(UsernamePasswordAuthenticationToken.authenticated(member+"@example.test",null,List.of()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void pendingPrivacyExportAndErasureRemainAvailableButCompanyScopesDoNot()throws Exception{
        var browser=new Browser(pending);
        assertThat(browser.grant("PERSONAL_EXPORT").path("token").asText()).hasSize(43);
        error(browser.post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope","COMPANY_EXPORT")),403,"EMAIL_VERIFICATION_REQUIRED");
        var job=jobs.create(pending,TransferJob.Kind.PERSONAL_EXPORT,UUID.randomUUID(),"a".repeat(64));
        assertThat(browser.get("/api/v1/account/data/jobs/"+job.id()).statusCode()).isEqualTo(200);
        var preview=browser.post("/api/v1/erasure/previews",Map.of("scope","ACCOUNT"));assertThat(preview.statusCode()).isEqualTo(200);
        error(browser.post("/api/v1/erasure/previews",Map.of("scope","COMPANY")),403,"EMAIL_VERIFICATION_REQUIRED");
        var p=json.readTree(preview.body());var grant=browser.grant("ACCOUNT_ERASURE");
        var confirm=browser.post("/api/v1/erasure/"+p.path("id").asText()+"/confirm",Map.of("digest",p.path("digest").asText(),"phrase",p.path("phrase").asText(),"acknowledgements",Set.of("IRREVERSIBLE","RETAINED_DATA","ALL_TRANSFER_FILES","BACKUP_RETENTION"),"receiptToken","e".repeat(43),"recentAuthGrant",grant.path("token").asText()));
        assertThat(confirm.statusCode()).isEqualTo(202);
        for(int i=0;i<150 && erasure.active();i++)erasure.runOnce();assertThat(erasure.active()).isFalse();
        assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,pending)).isEqualTo("ERASED");
    }
    @Test void workerPolicyAllowsPendingPersonalWorkAndRevokesEpochOrProofChanges()throws Exception{
        assertThatThrownBy(()->jobs.create(legacy,TransferJob.Kind.COMPANY_EXPORT,UUID.randomUUID(),"a".repeat(64))).isInstanceOf(IllegalStateException.class);
        var personal=jobs.create(pending,TransferJob.Kind.PERSONAL_EXPORT,UUID.randomUUID(),"b".repeat(64));
        var output=new java.io.ByteArrayOutputStream();
        assertThat(personalSnapshots.extract(personal,output,rows->{}).rows()).isGreaterThanOrEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(pending+"@example.test").doesNotContain(admin+"@example.test");
        var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();assertThat(jobs.heartbeat(lease)).isTrue();
        jdbc.update("UPDATE app_user SET auth_epoch=auth_epoch+1 WHERE id=?",pending);
        assertThat(jobs.heartbeat(lease)).isFalse();assertThat(jobs.status(pending,personal.id()).errorCode()).isEqualTo(TransferJob.Failure.AUTHORIZATION_REVOKED);
        var company=jobs.create(admin,TransferJob.Kind.COMPANY_EXPORT,UUID.randomUUID(),"c".repeat(64));
        jdbc.update("UPDATE app_user SET email_verified_at=NULL WHERE id=?",admin);
        assertThat(jobs.claim(UUID.randomUUID())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,company.id())).isEqualTo("AUTHORIZATION_REVOKED");
    }
    @Test void protectedWriteHoldsAuthorizationUntilCommitAndLaterStaleWritesFail()throws Exception{
        var authentication=principal(admin,UserRole.ADMINISTRATOR);var ready=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var write=pool.submit(()->{
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try{return new TransactionTemplate(transactions).execute(tx->{
                    var board=boards.create(new CreateBoardRequest("serialized-board","Serialized board","A protected write"));
                    ready.countDown();try{assertThat(commit.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return board;
                });}finally{SecurityContextHolder.clearContext();}
            });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            var change=pool.submit(()->jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",admin));
            try {assertThatThrownBy(()->change.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{commit.countDown();}
            assertThat(write.get(5,TimeUnit.SECONDS)).isNotNull();assertThat(change.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            assertThatThrownBy(()->boards.create(new CreateBoardRequest("stale-board","Stale board","Must not commit"))).isInstanceOf(ApiFailure.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM board WHERE slug='stale-board'",Long.class)).isZero();
        }finally{commit.countDown();}
    }
}
