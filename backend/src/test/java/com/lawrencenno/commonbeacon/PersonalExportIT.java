package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.lawrencenno.commonbeacon.identity.LoginRateLimiter;
import com.lawrencenno.commonbeacon.transfer.archive.*;
import com.lawrencenno.commonbeacon.transfer.export.*;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false"})
@Import({PostgresTestConfiguration.class,PersonalExportIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class PersonalExportIT {
    static final String PASSWORD="company-export-test-password";
    static final UUID ADMIN=id(3);
    static final Path ROOT=root();
    static final AtomicReference<Runnable> DURING_WRITE=new AtomicReference<>();
    static Path root(){try{return Files.createTempDirectory("commonbeacon-export-");}catch(IOException e){throw new UncheckedIOException(e);}}
    static UUID id(int n){return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));}
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean ArtifactStore testArtifactStore() throws IOException {
            var local=new LocalArtifactStore(ROOT,2147483648L,0);
            return new ArtifactStore() {
                public Stored write(UUID key,long limit,Writer writer)throws IOException {
                    return local.write(key,limit,out->writer.write(new FilterOutputStream(out) {
                        public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                        public void write(byte[] b,int off,int len)throws IOException{
                            out.write(b,off,len);var hook=DURING_WRITE.getAndSet(null);if(hook!=null)hook.run();
                        }
                    }));
                }
                public Optional<Stored> inspect(UUID key)throws IOException{return local.inspect(key);}
                public InputStream open(UUID key)throws IOException{return local.open(key);}
                public void delete(UUID key)throws IOException{local.delete(key);}
                public Set<UUID> keysOlderThan(Instant cutoff)throws IOException{return local.keysOlderThan(cutoff);}
            };
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferJobs jobs;
    @Autowired ArtifactStore store;
    @Autowired CompanySnapshot snapshot;
    @Autowired PersonalSnapshot personal;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean LoginRateLimiter loginLimiter;
    @MockitoBean(name="transferClock") Clock clock;
    static final java.util.concurrent.atomic.AtomicInteger WINDOWS=new java.util.concurrent.atomic.AtomicInteger();
    Map<String,List<JsonNode>> fixture=new LinkedHashMap<>();
    @BeforeEach void setup() throws Exception {
        when(loginLimiter.allow(anyString())).thenReturn(true);DURING_WRITE.set(null);
        Instant now=Instant.parse("2026-09-22T00:00:00Z").plusSeconds(1000L*WINDOWS.incrementAndGet());
        when(clock.instant()).thenReturn(now);when(clock.millis()).thenReturn(now.toEpochMilli());
        jdbc.execute("TRUNCATE app_user,board CASCADE");
        for(var key:store.keysOlderThan(Instant.now().plusSeconds(1)))store.delete(key);
        for(String entity:List.of("users","boards","questions","replies","articles","reports","actions","acceptances","contacts")) {
            try(var stream=getClass().getResourceAsStream("/data-transfer/v1/company-full/"+entity+".jsonl")) {
                fixture.put(entity,new BufferedReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).lines().map(json::readTree).toList());
            }
        }
        for(var user:fixture.get("users")) {
            UUID id=UUID.fromString(user.get("id").asText());
            jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role,account_state,created_at) VALUES (?,?,?,?,?,?,?)",
                id,id+"@example.test",user.get("displayName").asText(),passwords.encode(PASSWORD),
                id.equals(ADMIN)?"ADMINISTRATOR":"MEMBER","ACTIVE",Timestamp.from(Instant.parse(user.get("createdAt").asText())));
        }
        var tables=Map.of("boards","board","questions","question","replies","reply","articles","knowledge_article","reports","content_report","actions","moderation_action");
        for(String entity:List.of("boards","questions","replies","articles","reports","actions"))for(var row:fixture.get(entity)) {
            var columns=new ArrayList<String>();var values=new ArrayList<Object>();
            for(var property:row.properties()) {
                String name=property.getKey();var value=property.getValue();if(name.equals("origin"))continue;
                columns.add(name.replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT));
                values.add(value.isNull()?null:value.isBoolean()?value.asBoolean():name.equals("id")||name.endsWith("Id")?UUID.fromString(value.asText()):name.endsWith("At")?Timestamp.from(Instant.parse(value.asText())):value.asText());
            }
            jdbc.update("INSERT INTO "+tables.get(entity)+"("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
        }
        for(var row:fixture.get("acceptances"))jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?",UUID.fromString(row.get("replyId").asText()),UUID.fromString(row.get("questionId").asText()));
    }
    @AfterAll static void cleanup() throws IOException {
        if(!ROOT.getFileName().toString().startsWith("commonbeacon-export-"))throw new IllegalStateException();
        try(var paths=Files.walk(ROOT)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    TransferJob create(UUID actor){return jobs.createPersonalExport(actor,UUID.randomUUID(),()->{});}
    CompanyExportWorker worker(){return new CompanyExportWorker(jobs,store,snapshot,personal);}
    Map<String,byte[]> unzip(byte[] bytes)throws IOException {
        var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertThat(result.put(entry.getName(),zip.readAllBytes())).isNull();}
        }return result;
    }
    Map<String,byte[]> archive(TransferJob job)throws IOException {
        assertThat(jobs.status(job.requester(),job.id()).state()).isEqualTo(TransferJob.State.READY);
        var download=jobs.downloadInfo(job.requester(),job.id());try(var input=store.open(download.artifact())){return unzip(input.readAllBytes());}
    }
    List<JsonNode> rows(Map<String,byte[]> files,String entity) {
        return new String(files.get(entity+".jsonl"),java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList();
    }
    void validate(Map<String,byte[]> files)throws IOException {
        var entries=new LinkedHashMap<String,ArchiveCodec.Input>();files.forEach((name,bytes)->{if(!name.equals("manifest.json"))entries.put(name,()->new ByteArrayInputStream(bytes));});
        assertThat(new ArchiveCodec().validate(files.get("manifest.json"),entries,row->{}).valid()).isTrue();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2,3})
    void exactPersonalProjectionNeverExpandsForAdministrators(int actorNumber)throws Exception {
        UUID actor=id(actorNumber);var job=create(actor);assertThat(worker().runOnce()).isTrue();
        var files=archive(job);validate(files);
        assertThat(files.keySet()).containsExactlyInAnyOrder("manifest.json","users.jsonl","boards.jsonl","questions.jsonl","replies.jsonl","acceptances.jsonl","articles.jsonl","reports.jsonl");
        var manifest=json.readTree(files.get("manifest.json"));
        assertThat(manifest.path("profile").asText()).isEqualTo("personal");
        assertThat(manifest.path("referencePolicy").asText()).isEqualTo("opaque-personal-context");
        var user=(ObjectNode)fixture.get("users").stream().filter(r->r.path("id").asText().equals(actor.toString())).findFirst().orElseThrow().deepCopy();
        user.remove("origin");user.put("email",actor+"@example.test").put("role",actor.equals(ADMIN)?"ADMINISTRATOR":"MEMBER");
        assertThat(rows(files,"users")).containsExactly(user);
        var ownedQuestions=fixture.get("questions").stream().filter(r->r.path("authorId").asText().equals(actor.toString())).toList();
        for(String entity:List.of("questions","replies","articles"))assertThat(rows(files,entity)).isEqualTo(
            fixture.get(entity).stream().filter(r->r.path("authorId").asText().equals(actor.toString())).toList());
        var boardIds=ownedQuestions.stream().map(r->r.path("boardId").asText()).toList();
        assertThat(rows(files,"boards")).isEqualTo(fixture.get("boards").stream().filter(r->boardIds.contains(r.path("id").asText())).map(r->{
            var n=json.createObjectNode();for(String key:List.of("id","slug","name"))n.set(key,r.get(key));return (JsonNode)n;
        }).toList());
        var questionIds=ownedQuestions.stream().map(r->r.path("id").asText()).toList();
        assertThat(rows(files,"acceptances")).isEqualTo(fixture.get("acceptances").stream().filter(r->questionIds.contains(r.path("questionId").asText())).toList());
        assertThat(rows(files,"reports")).isEqualTo(fixture.get("reports").stream().filter(r->r.path("reporterId").asText().equals(actor.toString())).map(r->{
            var n=json.createObjectNode();for(String key:List.of("id","questionId","replyId","reason","createdAt"))n.set(key,r.get(key));return (JsonNode)n;
        }).toList());
        for(String entity:List.of("users","reports"))for(var row:rows(files,entity))assertThat(row.toString()).doesNotContain("password","resolverId","resolvedAt","resolutionDecision","resolutionNote","authRevision");
        var rejected=new ArchiveCodec().validateCompanyImport(files.get("manifest.json"),Map.of(),row->{throw new AssertionError("Personal rows must never reach company activation");});
        assertThat(rejected.valid()).isFalse();assertThat(rejected.issues()).extracting(ArchiveFormat.Issue::code).contains("COMPANY_PROFILE_REQUIRED");
    }
    @Test void unusedAccountGetsOnlyOwnProfileAndEmptySections()throws Exception {
        UUID actor=id(90);jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Empty account',?,'MEMBER')",actor,"empty@example.test",passwords.encode(PASSWORD));
        var job=create(actor);worker().runOnce();var files=archive(job);validate(files);
        assertThat(rows(files,"users")).hasSize(1);
        for(String entity:List.of("boards","questions","replies","acceptances","articles","reports"))assertThat(rows(files,entity)).isEmpty();
    }
    @Test void requesterRevisionChangesPreventPublication() {
        var job=create(id(1));jdbc.update("UPDATE app_user SET role='MODERATOR' WHERE id=?",id(1));
        assertThat(worker().runOnce()).isFalse();assertThat(jobs.status(id(1),job.id()).errorCode()).isEqualTo(TransferJob.Failure.AUTHORIZATION_REVOKED);
    }
    @Test void personalJobsCannotUseTheCompanyProjection() {
        var job=create(id(1));var output=new ByteArrayOutputStream();
        assertThatThrownBy(()->snapshot.extract(job,output,rows->{})).isInstanceOf(IllegalArgumentException.class);
        assertThat(output.size()).isZero();
    }
    @Test void personalSnapshotDoesNotMixConcurrentChanges()throws Exception {
        var job=create(id(1));var out=new ByteArrayOutputStream();var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var dataset=personal.extract(job,out,count->{if(changed.compareAndSet(false,true))CompletableFuture.runAsync(()->
            jdbc.update("UPDATE question SET body='Changed after snapshot started' WHERE author_id=?",id(1))).join();});
        byte[] bytes=out.toByteArray();var files=new LinkedHashMap<String,byte[]>();files.put("manifest.json",dataset.manifest());
        for(var entry:dataset.entries())files.put(entry.entity().file(),Arrays.copyOfRange(bytes,(int)entry.offset(),(int)(entry.offset()+entry.bytes())));
        validate(files);assertThat(rows(files,"questions")).isEqualTo(fixture.get("questions").stream().filter(r->r.path("authorId").asText().equals(id(1).toString())).toList());
    }
    @Test void httpScopeForgeryOwnershipReplayAndProtectedDownload()throws Exception {
        String route="/api/v1/account/data/exports";String root="/api/v1/account/data/jobs/";
        try(var owner=new Browser(id(1));var other=new Browser(id(2));var admin=new Browser(ADMIN);var visitor=new Browser(null)) {
            var headers=Map.of("Idempotency-Key",UUID.randomUUID().toString());
            var dummy=Map.of("recentAuthGrant","a".repeat(43));
            assertThat(visitor.post(route,dummy,headers).statusCode()).isEqualTo(401);
            assertThat(owner.send("POST",route,json.writeValueAsString(dummy),"application/json",false,headers).statusCode()).isEqualTo(403);
            assertThat(owner.post(route,Map.of("recentAuthGrant",owner.grant("DOWNLOAD")),headers).statusCode()).isEqualTo(403);
            String grant=owner.grant("PERSONAL_EXPORT");
            for(String field:List.of("userId","requesterId","scope","includeContacts")) {
                assertThat(owner.post(route,Map.of("recentAuthGrant",grant,field,ADMIN.toString()),headers).statusCode()).isEqualTo(400);
            }
            var body=Map.of("recentAuthGrant",grant);var response=owner.post(route,body,headers);assertThat(response.statusCode()).isEqualTo(202);
            UUID job=UUID.fromString(json.readTree(response.body()).path("id").asText());
            assertThat(owner.post(route,body,headers).statusCode()).isEqualTo(202);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_job",Integer.class)).isEqualTo(1);
            for(var outsider:List.of(other,admin)) {
                assertThat(outsider.get(root+job).statusCode()).isEqualTo(404);
                assertThat(outsider.post(root+job+"/cancel",Map.of("expectedVersion",0),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode()).isEqualTo(404);
                assertThat(outsider.post(root+job+"/download-ticket",Map.of("recentAuthGrant","a".repeat(43)),Map.of()).statusCode()).isEqualTo(404);
                assertThat(json.readTree(outsider.get(root.substring(0,root.length()-1)).body()).path("items").isEmpty()).isTrue();
            }
            assertThat(admin.get("/api/v1/admin/data/jobs/"+job).statusCode()).isEqualTo(404);
            worker().runOnce();var files=unzip(owner.download(job));validate(files);
            assertThat(rows(files,"users").getFirst().path("id").asText()).isEqualTo(id(1).toString());
            jdbc.update("UPDATE transfer_artifact SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",job);
            assertThat(owner.post(root+job+"/download-ticket",Map.of("recentAuthGrant",owner.grant("DOWNLOAD")),Map.of()).statusCode()).isEqualTo(410);
        }
    }
    class Browser implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf;
        Browser(UUID actor)throws Exception {
            csrf=json.readTree(get("/api/v1/auth/csrf").body());
            if(actor!=null) {
                var response=send("POST","/api/v1/auth/login","email="+actor+"%40example.test&password="+PASSWORD,"application/x-www-form-urlencoded",true,Map.of());
                assertThat(response.statusCode()).isEqualTo(200);csrf=json.readTree(get("/api/v1/auth/csrf").body());
            }
        }
        HttpResponse<String> send(String method,String path,String body,String type,boolean token,Map<String,String> headers)throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if(type!=null)request.header("Content-Type",type);if(token)request.header(csrf.get("headerName").asText(),csrf.get("token").asText());headers.forEach(request::header);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null,false,Map.of());}
        HttpResponse<String> post(String path,Object body,Map<String,String> headers)throws Exception{return send("POST",path,json.writeValueAsString(body),"application/json",true,headers);}
        String grant(String scope)throws Exception {
            var response=post("/api/v1/account/data/reauthentication",Map.of("password",PASSWORD,"scope",scope),Map.of());
            assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).get("token").asText();
        }
        byte[] download(UUID job)throws Exception {
            var ticket=post("/api/v1/account/data/jobs/"+job+"/download-ticket",Map.of("recentAuthGrant",grant("DOWNLOAD")),Map.of());
            assertThat(ticket.statusCode()).isEqualTo(200);
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/account/data/jobs/"+job+"/download"))
                .header("X-Download-Ticket",json.readTree(ticket.body()).get("token").asText()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Content-Type")).contains("application/zip");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).isEqualTo("attachment; filename=\"commonbeacon-"+job+".zip\"");
            return response.body();
        }
        public void close(){client.close();}
    }

}
