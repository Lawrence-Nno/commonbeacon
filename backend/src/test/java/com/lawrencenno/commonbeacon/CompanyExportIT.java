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
@Import({PostgresTestConfiguration.class,CompanyExportIT.Storage.class})
@org.springframework.test.context.ActiveProfiles("local")
class CompanyExportIT {
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
                id,id.equals(ADMIN)?id+"@example.test":null,user.get("displayName").asText(),id.equals(ADMIN)?passwords.encode(PASSWORD):null,
                id.equals(ADMIN)?"ADMINISTRATOR":"MEMBER",id.equals(ADMIN)?"ACTIVE":"IMPORTED_INACTIVE",Timestamp.from(Instant.parse(user.get("createdAt").asText())));
        }
        jdbc.update("INSERT INTO imported_author VALUES (?,?,?,?)",id(200),id(201),id(1),"alex@example.test");
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
    TransferJob create(boolean contacts,boolean history){return jobs.createCompanyExport(ADMIN,UUID.randomUUID(),contacts,history,()->{});}
    CompanyExportWorker worker(){return new CompanyExportWorker(jobs,store,snapshot);}
    Map<String,byte[]> unzip(byte[] bytes)throws IOException {
        var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertThat(result.put(entry.getName(),zip.readAllBytes())).isNull();}
        }return result;
    }
    Map<String,byte[]> archive(TransferJob job)throws IOException {
        assertThat(jobs.status(ADMIN,job.id()).state()).isEqualTo(TransferJob.State.READY);
        var download=jobs.downloadInfo(ADMIN,job.id());try(var input=store.open(download.artifact())){return unzip(input.readAllBytes());}
    }
    List<JsonNode> rows(Map<String,byte[]> files,String entity) {
        return new String(files.get(entity+".jsonl"),java.nio.charset.StandardCharsets.UTF_8).lines().map(json::readTree).toList();
    }
    void validate(Map<String,byte[]> files)throws IOException {
        var entries=new LinkedHashMap<String,ArchiveCodec.Input>();files.forEach((name,bytes)->{if(!name.equals("manifest.json"))entries.put(name,()->new ByteArrayInputStream(bytes));});
        assertThat(new ArchiveCodec().validate(files.get("manifest.json"),entries,row->{}).valid()).isTrue();
    }
    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void exportsExactFixtureFieldsCountsDigestsAndIndependentPrivateOptions(boolean contacts,boolean history)throws Exception {
        var job=create(contacts,history);assertThat(worker().runOnce()).isTrue();var files=archive(job);validate(files);
        assertThat(files.containsKey("contacts.jsonl")).isEqualTo(contacts);
        assertThat(files.containsKey("reports.jsonl")).isEqualTo(history);assertThat(files.containsKey("actions.jsonl")).isEqualTo(history);
        for(String entity:List.of("boards","questions","replies","acceptances","articles"))assertThat(rows(files,entity)).isEqualTo(fixture.get(entity));
        var expectedUsers=new ArrayList<JsonNode>();for(var user:fixture.get("users")) {
            var expected=(ObjectNode)user.deepCopy();if(expected.get("id").asText().equals(id(1).toString()))expected.putObject("origin").put("sourceInstanceId",id(200).toString()).put("sourceId",id(201).toString());expectedUsers.add(expected);
        }
        assertThat(rows(files,"users")).isEqualTo(expectedUsers);
        if(history) {
            assertThat(rows(files,"reports")).isEqualTo(fixture.get("reports"));
            assertThat(rows(files,"actions")).isEqualTo(fixture.get("actions").stream().map(row->{var copy=(ObjectNode)row.deepCopy();copy.remove("origin");return copy;}).toList());
        }
        if(contacts)assertThat(rows(files,"contacts")).hasSize(2);
        for(var bytes:files.values())assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("password_hash","auth_revision","search_vector","ADMINISTRATOR","IMPORTED_INACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_artifact WHERE job_id=? AND purpose='INTERMEDIATE' AND state='DELETED'",Integer.class,job.id())).isEqualTo(1);
    }
    @Test void snapshotRemainsConsistentAcrossConcurrentEditsHidesAndPublication()throws Exception {
        var job=create(true,true);var output=new ByteArrayOutputStream();var changed=new java.util.concurrent.atomic.AtomicBoolean();
        CompanySnapshot.Dataset dataset;
        try(var executor=Executors.newSingleThreadExecutor()) {
            dataset=snapshot.extract(job,output,count->{
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
                if(count>=5 && changed.compareAndSet(false,true))try {
                    executor.submit(()->{
                        jdbc.update("UPDATE question SET title='Concurrent edit',visibility='HIDDEN' WHERE id=?",id(20));
                        jdbc.update("UPDATE reply SET body='Concurrent reply edit' WHERE id=?",id(30));
                        jdbc.update("UPDATE knowledge_article SET status='PUBLISHED',published_at=clock_timestamp() WHERE id=?",id(40));
                    }).get(10,TimeUnit.SECONDS);
                }catch(Exception e){throw new IOException(e);}
            });
        }
        assertThat(changed).isTrue();assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        byte[] bytes=output.toByteArray();var files=new LinkedHashMap<String,byte[]>();files.put("manifest.json",dataset.manifest());
        for(var entry:dataset.entries())files.put(entry.entity().file(),Arrays.copyOfRange(bytes,(int)entry.offset(),(int)(entry.offset()+entry.bytes())));
        validate(files);for(String entity:List.of("questions","replies","articles"))assertThat(rows(files,entity)).isEqualTo(fixture.get(entity));
        assertThat(jdbc.queryForObject("SELECT title FROM question WHERE id=?",String.class,id(20))).isEqualTo("Concurrent edit");
    }
    @Test void recoveredWorkerDiscardsOldDatasetAndRestartsAllPages()throws Exception {
        var job=create(false,false);var old=jobs.claimCompanyExport(UUID.randomUUID()).orElseThrow();
        UUID partial=jobs.beginArtifact(old.lease(),"INTERMEDIATE",268435456L);store.write(partial,268435456L,out->out.write("partial old snapshot".getBytes()));
        jobs.checkpoint(old.lease(),12);jdbc.update("UPDATE transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job.id());
        jdbc.update("UPDATE question SET title='After crashed snapshot' WHERE id=?",id(20));
        assertThat(worker().runOnce()).isTrue();var files=archive(job);validate(files);
        assertThat(rows(files,"questions").getFirst().get("title").asText()).isEqualTo("After crashed snapshot");
        assertThat(store.inspect(partial)).isEmpty();assertThat(jobs.status(ADMIN,job.id()).attempts()).isEqualTo(2);
        assertThatThrownBy(()->jobs.checkpoint(old.lease(),13)).hasMessage("STALE_LEASE");
    }
    @Test void invalidSourceAndCountLimitNeverPublishAnArchive() {
        jdbc.update("UPDATE question SET title=? WHERE id=?",new String(Character.toChars(0x1F600)).repeat(150),id(20));
        var job=create(false,false);worker().runOnce();assertThat(jobs.status(ADMIN,job.id()).errorCode()).isEqualTo(TransferJob.Failure.INVALID_SOURCE_DATA);
        assertThat(jobs.artifactAvailable(ADMIN,job.id())).isFalse();
        jdbc.update("UPDATE question SET title='Valid title again' WHERE id=?",id(20));
        new ArtifactReconciler(jobs,store).scheduledReconciliation();
        for(int i=0;i<100;i++)jdbc.update("INSERT INTO board(id,slug,name,description) VALUES (?,?,'Overflow board','Count limit')",UUID.randomUUID(),"overflow-"+i);
        var large=create(false,false);worker().runOnce();assertThat(jobs.status(ADMIN,large.id()).errorCode()).isEqualTo(TransferJob.Failure.TRANSFER_LIMIT_EXCEEDED);
        assertThat(jobs.artifactAvailable(ADMIN,large.id())).isFalse();
    }
    @Test void invalidAcceptedReplyRelationshipIsReportedWithoutRepairingSource() {
        jdbc.update("UPDATE reply SET visibility='HIDDEN' WHERE id=?",id(30));
        var job=create(false,false);worker().runOnce();
        assertThat(jobs.status(ADMIN,job.id()).errorCode()).isEqualTo(TransferJob.Failure.INVALID_SOURCE_DATA);
        assertThat(jobs.artifactAvailable(ADMIN,job.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?",UUID.class,id(20))).isEqualTo(id(30));
    }
    @Test void cancellationAndRevocationDuringSnapshotDiscardPrivatePartials()throws Exception {
        var job=create(false,false);
        DURING_WRITE.set(()->jobs.cancel(ADMIN,job.id(),jobs.status(ADMIN,job.id()).version()));worker().runOnce();
        assertThat(jobs.status(ADMIN,job.id()).state()).isEqualTo(TransferJob.State.CANCELLED);
        assertThat(store.keysOlderThan(Instant.now().plusSeconds(1))).isEmpty();
        new ArtifactReconciler(jobs,store).reconcile();
        // Revocation must leave a usable recovery administrator.
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,'recovery@example.test','Recovery',?,'ADMINISTRATOR')",UUID.randomUUID(),passwords.encode(PASSWORD));
        var revoked=create(false,false);DURING_WRITE.set(()->{
            try(var executor=Executors.newSingleThreadExecutor()) {
                executor.submit(()->jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",ADMIN)).get(10,TimeUnit.SECONDS);
            }catch(Exception e){throw new IllegalStateException(e);}
        });worker().runOnce();
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,revoked.id())).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(store.keysOlderThan(Instant.now().plusSeconds(1))).isEmpty();
    }
    @Test void privateDiskAdmissionFailureNeverPublishes()throws Exception {
        var job=create(false,false);Path small=Files.createTempDirectory("commonbeacon-export-small-");
        try {
            var limited=new LocalArtifactStore(small,1024,0);new CompanyExportWorker(jobs,limited,snapshot).runOnce();
            assertThat(jobs.status(ADMIN,job.id()).state()).isEqualTo(TransferJob.State.FAILED);assertThat(jobs.artifactAvailable(ADMIN,job.id())).isFalse();
            assertThat(limited.keysOlderThan(Instant.now().plusSeconds(1))).isEmpty();
        }finally{Files.deleteIfExists(small.resolve(".store.lock"));Files.delete(small);}
    }
    @Test void highCompressionEntriesUseStoredZipMethod()throws Exception {
        // Repeated long bodies exercise the 100:1 fallback without a large heap fixture.
        for(int i=0;i<40;i++)jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,author_id) VALUES (?,?,'Compression fixture',?,?)",UUID.randomUUID(),"compression-"+i,"x".repeat(20000),ADMIN);
        var job=create(false,false);worker().runOnce();var file=jobs.downloadInfo(ADMIN,job.id());boolean found=false;
        try(var zip=new ZipInputStream(store.open(file.artifact()))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null) {if(entry.getName().equals("articles.jsonl")){assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);found=true;}zip.closeEntry();}
        }assertThat(found).isTrue();validate(archive(job));
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
            var ticket=post("/api/v1/admin/data/jobs/"+job+"/download-ticket",Map.of("recentAuthGrant",grant("DOWNLOAD")),Map.of());
            assertThat(ticket.statusCode()).isEqualTo(200);
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/admin/data/jobs/"+job+"/download"))
                .header("X-Download-Ticket",json.readTree(ticket.body()).get("token").asText()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Content-Type")).contains("application/zip");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).isEqualTo("attachment; filename=\"commonbeacon-"+job+".zip\"");
            return response.body();
        }
        public void close(){client.close();}
    }
    @Test void httpCreationReplayPollingProtectedDownloadsAndExpiry()throws Exception {
        try(var browser=new Browser(ADMIN)) {
            String grant=browser.grant("COMPANY_EXPORT");UUID key=UUID.randomUUID();
            var body=Map.of("includeContacts",true,"includeModerationHistory",true,"acknowledgedPrivateContent",true,"recentAuthGrant",grant);
            var response=browser.post("/api/v1/admin/data/exports",body,Map.of("Idempotency-Key",key.toString()));
            assertThat(response.statusCode()).isEqualTo(202);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            UUID id=UUID.fromString(json.readTree(response.body()).get("id").asText());
            assertThat(browser.post("/api/v1/admin/data/exports",body,Map.of("Idempotency-Key",key.toString())).statusCode()).isEqualTo(202);
            var changed=new HashMap<String,Object>(body);changed.put("includeContacts",false);
            assertThat(browser.post("/api/v1/admin/data/exports",changed,Map.of("Idempotency-Key",key.toString())).statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_job",Integer.class)).isEqualTo(1);
            worker().runOnce();assertThat(json.readTree(browser.get("/api/v1/admin/data/jobs/"+id).body()).get("state").asText()).isEqualTo("READY");
            byte[] first=browser.download(id);validate(unzip(first));assertThat(browser.download(id)).isEqualTo(first);
            long auditDeadline=System.nanoTime()+5_000_000_000L;
            while(jdbc.queryForObject("SELECT count(*) FROM transfer_audit WHERE job_id=? AND event='DOWNLOAD_COMPLETED'",Integer.class,id)<2 && System.nanoTime()<auditDeadline)Thread.sleep(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_audit WHERE job_id=? AND event='DOWNLOAD_COMPLETED'",Integer.class,id)).isEqualTo(2);
            assertThat(browser.post("/api/v1/admin/data/exports",body,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
            jdbc.update("UPDATE transfer_artifact SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",id);
            var expired=browser.post("/api/v1/admin/data/jobs/"+id+"/download-ticket",Map.of("recentAuthGrant",browser.grant("DOWNLOAD")),Map.of());
            assertThat(expired.statusCode()).isEqualTo(410);
        }
    }
    @Test void httpRejectsUnauthorizedUnconfirmedWrongScopeAndCrossRequesterRequests()throws Exception {
        UUID moderator=id(90),other=id(91);
        for(var actor:List.of(moderator,other))jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Other actor',?,?)",
            actor,actor+"@example.test",passwords.encode(PASSWORD),actor.equals(moderator)?"MODERATOR":"ADMINISTRATOR");
        String route="/api/v1/admin/data/exports";var headers=Map.of("Idempotency-Key",UUID.randomUUID().toString());
        var body=new HashMap<String,Object>(Map.of("acknowledgedPrivateContent",true,"recentAuthGrant","a".repeat(43)));
        try(var visitor=new Browser(null);var mod=new Browser(moderator);var admin=new Browser(ADMIN);var outsider=new Browser(other)) {
            assertThat(visitor.post(route,body,headers).statusCode()).isEqualTo(401);
            assertThat(mod.post(route,body,headers).statusCode()).isEqualTo(403);
            assertThat(admin.send("POST",route,json.writeValueAsString(body),"application/json",false,headers).statusCode()).isEqualTo(403);
            body.put("acknowledgedPrivateContent",false);assertThat(admin.post(route,body,headers).statusCode()).isEqualTo(400);
            body.put("acknowledgedPrivateContent",true);body.put("recentAuthGrant",admin.grant("DOWNLOAD"));
            assertThat(admin.post(route,body,headers).statusCode()).isEqualTo(403);
            body.put("recentAuthGrant",admin.grant("COMPANY_EXPORT"));var response=admin.post(route,body,headers);assertThat(response.statusCode()).isEqualTo(202);
            String id=json.readTree(response.body()).get("id").asText();
            assertThat(outsider.get("/api/v1/admin/data/jobs/"+id).statusCode()).isEqualTo(404);
            jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",ADMIN);
            assertThat(admin.post(route,body,headers).statusCode()).isEqualTo(403);
        }
    }

}
