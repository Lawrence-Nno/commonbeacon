package com.lawrencenno.commonbeacon;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lawrencenno.commonbeacon.moderation.OperationalSummaryService;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=summary-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalSummaryIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OperationalSummaryService summaries;
    @Autowired PlatformTransactionManager transactions;
    Browser visitor, member, moderator, admin;
    UUID owner, adminId, moderatorId, board;
    class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base() { return "http://127.0.0.1:" + port; }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode read(String path) throws Exception {
            var response = get(path); assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body());
        }
        JsonNode mutate(String path, Map<String, ?> body, int status, String method) throws Exception {
            var csrf = read("/api/v1/auth/csrf");
            var response = client.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json")
                    .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(status); return json.readTree(response.body());
        }
        UUID login(String email) throws Exception {
            var csrf = read("/api/v1/auth/csrf");
            var response = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=summary-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); return UUID.fromString(json.readTree(response.body()).get("id").asText());
        }
        public void close() { client.close(); }
    }
    @BeforeAll void sessions() throws Exception {
        visitor = new Browser(); member = new Browser(); moderator = new Browser(); admin = new Browser();
        owner = member.login("alex.member@example.test"); moderatorId = moderator.login("morgan.moderator@example.test"); adminId = admin.login("avery.admin@example.test");
    }
    @AfterAll void close() { for (var actor : List.of(visitor, member, moderator, admin)) actor.close(); }
    @BeforeEach void resetDedicatedTestDatabase() {
        // This Spring context owns its own disposable PostgreSQL container.
        jdbc.execute("TRUNCATE TABLE board, knowledge_article, content_report, moderation_action CASCADE");
        board = UUID.randomUUID();
        jdbc.update("INSERT INTO board(id,slug,name,description) VALUES (?,?,?,?)", board, "summary-" + board, "Summary board", "Fictional summary board");
    }
    UUID question(String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO question(id,board_id,author_id,title,body,visibility) VALUES (?,?,?,?,?,?)", id, board, owner, "Fictional summary question", "Private fixture text for counting", visibility); return id;
    }
    UUID reply(UUID question, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reply(id,question_id,author_id,body,visibility) VALUES (?,?,?,?,?)", id, question, adminId, "Fictional summary reply", visibility); return id;
    }
    void accept(UUID question, UUID reply) { jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", reply, question); }
    UUID report(UUID question, UUID reporter) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,?)", id, reporter, question, "Private fixture report reason"); return id;
    }
    UUID article(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,status,author_id,published_at) VALUES (?,?,?,?,?,?,CASE WHEN ?='PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END)", id, "summary-" + id, "Summary article title", "Private article fixture body", status, adminId, status); return id;
    }
    void counts(long unanswered, long reports, long articles) throws Exception {
        var summary = moderator.read("/api/v1/moderation/summary");
        assertThat(summary.get("unansweredQuestions").asLong()).isEqualTo(unanswered);
        assertThat(summary.get("openReports").asLong()).isEqualTo(reports);
        assertThat(summary.get("publishedArticles").asLong()).isEqualTo(articles);
    }

    @Test void protectsHttpAndServiceAccessAndReturnsOnlyNoStoreCounts() throws Exception {
        assertThat(visitor.get("/api/v1/moderation/summary").statusCode()).isEqualTo(401);
        assertThat(member.get("/api/v1/moderation/summary").statusCode()).isEqualTo(403);
        for (var actor : List.of(moderator, admin)) {
            var response = actor.get("/api/v1/moderation/summary");
            assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("cache-control")).contains("no-store");
            var value = json.readTree(response.body());
            assertThat(value.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("unansweredQuestions", "openReports", "publishedArticles");
            assertThat(value.properties().stream().map(entry -> entry.getValue().asLong()).toList()).containsOnly(0L);
            assertThat(actor.get("/api/v1/moderation/summary?status=OPEN").statusCode()).isEqualTo(400);
        }
        assertThatThrownBy(() -> summaries.get()).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("member", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))));
        try { assertThatThrownBy(() -> summaries.get()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class); }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void countsEffectiveSolutionsReportRowsAndPublishedArticlesExactly() throws Exception {
        UUID empty = question("VISIBLE"), unselected = question("VISIBLE"), solved = question("VISIBLE"), stale = question("VISIBLE"), hiddenSolved = question("HIDDEN");
        reply(unselected, "VISIBLE"); accept(solved, reply(solved, "VISIBLE")); accept(stale, reply(stale, "HIDDEN")); accept(hiddenSolved, reply(hiddenSolved, "VISIBLE"));
        question("HIDDEN");
        UUID archived = UUID.randomUUID();
        jdbc.update("INSERT INTO board(id,slug,name,description,archived) VALUES (?,?,?, ?,true)", archived, "archived-" + archived, "Archived", "Archived fixture board");
        UUID original = board; board = archived; question("VISIBLE"); board = original;
        report(empty, owner); report(empty, moderatorId);
        UUID resolved = report(unselected, owner);
        jdbc.update("UPDATE content_report SET status='RESOLVED',resolver_id=?,resolved_at=CURRENT_TIMESTAMP,resolution_decision='DISMISS',resolution_note='Fictional resolution note' WHERE id=?", moderatorId, resolved);
        article("DRAFT"); article("PUBLISHED"); article("ARCHIVED");
        counts(4, 2, 1);
        assertThat(admin.get("/api/v1/moderation/summary").body()).doesNotContain("Private", empty.toString(), "draft", "reason");
    }

    void hide(String target, UUID id) throws Exception {
        var receipt = member.mutate("/api/v1/reports", Map.of(target + "Id", id, "reason", "Fictional summary moderation concern"), 201, "POST");
        String path = "/api/v1/moderation/reports/" + receipt.get("id").asText();
        var review = moderator.read(path);
        Map<String, Object> body = new HashMap<>();
        body.put("decision", "HIDE"); body.put("resolutionNote", "Fictional summary hide action"); body.put("expectedVersion", review.get("report").get("version").asLong());
        body.put("expectedTargetVersion", review.get("context").get(target).get("version").asLong());
        if (target.equals("reply")) body.put("expectedQuestionVersion", review.get("context").get("question").get("version").asLong());
        moderator.mutate(path + "/resolve", body, 200, "POST");
    }
    void restore(String kind, UUID id) throws Exception {
        String path = "/api/v1/moderation/" + kind + "/" + id;
        var context = moderator.read(path); var body = new HashMap<String, Object>();
        body.put("reason", "Fictional summary restore action");
        body.put("expectedTargetVersion", context.get(kind.equals("replies") ? "reply" : "question").get("version").asLong());
        if (kind.equals("replies")) body.put("expectedQuestionVersion", context.get("question").get("version").asLong());
        moderator.mutate(path + "/restore", body, 200, "POST");
    }
    @Test void realAcceptanceAndModerationTransitionsPreserveUnansweredSemantics() throws Exception {
        UUID q = question("VISIBLE"), r = reply(q, "VISIBLE"); counts(1, 0, 0);
        member.mutate("/api/v1/questions/" + q + "/accepted-reply", Map.of("replyId", r, "expectedVersion", 0), 200, "PUT"); counts(0, 0, 0);
        hide("reply", r); counts(1, 0, 0);
        restore("replies", r); counts(1, 0, 0);
        var current = member.read("/api/v1/questions/" + q);
        member.mutate("/api/v1/questions/" + q + "/accepted-reply", Map.of("replyId", r, "expectedVersion", current.get("version").asLong()), 200, "PUT"); counts(0, 0, 0);
        hide("question", q); counts(0, 0, 0);
        restore("questions", q); counts(0, 0, 0);
        assertThat(member.read("/api/v1/questions/" + q).get("solved").asBoolean()).isTrue();
        UUID unanswered = question("VISIBLE"); counts(1, 0, 0);
        hide("question", unanswered); counts(0, 0, 0);
        restore("questions", unanswered); counts(1, 0, 0);
    }

    @Test void oneResponseCannotMixCountsAcrossAnAtomicTransition() throws Exception {
        UUID q = question("VISIBLE"), report = report(q, owner), article = article("DRAFT");
        var running = new AtomicBoolean(true); var writes = new AtomicInteger(); var started = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                var tx = new TransactionTemplate(transactions);
                for (int i = 0; i < 2000 && running.get(); i++) {
                    final boolean published = i % 2 == 0;
                    tx.executeWithoutResult(status -> {
                        jdbc.update("UPDATE knowledge_article SET status=?,published_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?", published ? "PUBLISHED" : "DRAFT", published, article);
                        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        if (published) jdbc.update("UPDATE content_report SET status='RESOLVED',resolver_id=?,resolved_at=CURRENT_TIMESTAMP,resolution_decision='DISMISS',resolution_note='Atomic fixture transition' WHERE id=?", moderatorId, report);
                        else jdbc.update("UPDATE content_report SET status='OPEN',resolver_id=NULL,resolved_at=NULL,resolution_decision=NULL,resolution_note=NULL WHERE id=?", report);
                    });
                    writes.incrementAndGet(); started.countDown();
                }
            });
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 40; i++) {
                    var result = admin.read("/api/v1/moderation/summary");
                    assertThat(result.get("openReports").asLong() + result.get("publishedArticles").asLong()).isEqualTo(1);
                    assertThat(result.get("unansweredQuestions").asLong()).isEqualTo(1);
                }
            } finally { running.set(false); writer.get(15, TimeUnit.SECONDS); }
            assertThat(writes.get()).isGreaterThan(1);
        }
    }
}
