package com.lawrencenno.commonbeacon;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=report-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.lawrencenno.commonbeacon.moderation.ReportService reportService;
    Browser member, other, moderator, admin, visitor;

    class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf;
        JsonNode user;
        String base() { return "http://127.0.0.1:" + port; }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        void token() throws Exception { csrf = json.readTree(get("/api/v1/auth/csrf").body()); }
        HttpResponse<String> send(String method, String path, Map<String, ?> body, boolean withCsrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
            if (withCsrf) request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        void login(String email) throws Exception {
            token();
            var response = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                            + "&password=report-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            user = json.readTree(response.body());
            token();
        }
        JsonNode create(UUID board) throws Exception {
            var response = send("POST", listPath(board), draft(), true);
            assertThat(response.statusCode()).isEqualTo(201);
            var created = json.readTree(response.body());
            assertThat(response.headers().firstValue("location")).contains(path(created));
            return created;
        }
        public void close() { client.close(); }
    }

    @BeforeAll void sessions() throws Exception {
        member = new Browser(); other = new Browser(); moderator = new Browser(); admin = new Browser(); visitor = new Browser();
        member.login("alex.member@example.test");
        other.login("sam.member@example.test");
        moderator.login("morgan.moderator@example.test");
        admin.login("avery.admin@example.test");
        visitor.token();
    }
    @AfterAll void closeSessions() {
        for (var browser : List.of(member, other, moderator, admin, visitor)) browser.close();
    }
    String listPath(UUID id) { return "/api/v1/boards/" + id + "/questions"; }
    String path(JsonNode question) { return "/api/v1/questions/" + question.get("id").asText(); }
    Map<String, Object> draft() { return Map.of("title", "  How do I get started?  ", "body", "  I would like help with the first steps.  "); }
    UUID board() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO board (id, slug, name, description) VALUES (?, ?, ?, ?)",
                id, "questions-" + id, "Questions test board", "A fictional board for integration tests.");
        return id;
    }


    JsonNode reply(JsonNode question, Browser actor) throws Exception {
        var response = actor.send("POST", repliesPath(question), Map.of("body", "  A helpful reply.  "), true);
        assertThat(response.statusCode()).isEqualTo(201);
        var reply = json.readTree(response.body());
        assertThat(response.headers().firstValue("location")).contains(replyPath(reply));
        return reply;
    }
    String repliesPath(JsonNode question) { return path(question) + "/replies"; }
    String replyPath(JsonNode reply) { return "/api/v1/replies/" + reply.get("id").asText(); }

    HttpResponse<String> report(Browser actor, String kind, String id) throws Exception {
        return actor.send("POST", "/api/v1/reports", Map.of(kind, id, "reason", "  Private report reason.  "), true);
    }
    UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }

    @Test void reportsDeriveReporterAndReturnOnlyReceiptWithoutChangingPublicContent() throws Exception {
        var question = member.create(board());
        var reply = reply(question, other);
        for (var actor : List.of(member, other, moderator, admin)) {
            var response = report(actor, "questionId", id(question).toString());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("location")).isEmpty();
            assertThat(response.headers().firstValue("cache-control")).contains("no-store");
            var receipt = json.readTree(response.body());
            assertThat(receipt.size()).isEqualTo(3);
            assertThat(receipt.get("status").asText()).isEqualTo("OPEN");
            assertThat(receipt.get("createdAt").asText()).isNotBlank();
            var row = jdbc.queryForMap("SELECT * FROM content_report WHERE id=?", id(receipt));
            assertThat(row.get("reporter_id").toString()).isEqualTo(actor.user.get("id").asText());
            assertThat(row.get("reason")).isEqualTo("Private report reason.");
            assertThat(row.get("version")).isEqualTo(0L);
            assertThat(row.get("resolution_note")).isNull();
            assertThat(report(actor, "replyId", id(reply).toString()).statusCode()).isEqualTo(201);
        }
        assertThat(json.readTree(visitor.get(path(question)).body())).isEqualTo(question);
        assertThat(json.readTree(visitor.get(replyPath(reply)).body())).isEqualTo(reply);
        assertThat(visitor.get(repliesPath(question)).body()).doesNotContain("Private report", "reporter", "resolution");
        assertThat(member.get("/api/v1/moderation/reports").statusCode()).isEqualTo(403);
    }

    @Test void invalidInputForgerySessionAndCsrfCannotInsertReports() throws Exception {
        var question = member.create(board());
        var reply = reply(question, other);
        var valid = Map.<String, Object>of("questionId", id(question).toString(), "reason", "Valid reason");
        assertThat(visitor.send("POST", "/api/v1/reports", valid, true).statusCode()).isEqualTo(401);
        assertThat(member.send("POST", "/api/v1/reports", valid, false).statusCode()).isEqualTo(403);
        for (var body : List.of(Map.of("reason", "Valid reason"),
                Map.of("questionId", id(question).toString(), "replyId", id(reply).toString(), "reason", "Valid reason"),
                Map.of("questionId", "not-a-uuid", "reason", "Valid reason"))) {
            var response = member.send("POST", "/api/v1/reports", body, true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_REQUEST");
        }
        for (var reason : List.of("", "    ", "four", "x".repeat(2001), "ðŸ˜€".repeat(1001))) {
            var response = member.send("POST", "/api/v1/reports", Map.of("questionId", id(question), "reason", reason), true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("VALIDATION_FAILED");
        }
        for (var field : List.of("reporterId", "status", "resolverId", "version", "unexpected")) {
            var forged = new HashMap<String, Object>(valid);
            forged.put(field, "forged");
            var response = member.send("POST", "/api/v1/reports", forged, true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_report WHERE question_id=?", Integer.class, id(question))).isZero();
        var nullable = new HashMap<String, Object>(valid);
        nullable.put("replyId", null);
        assertThat(member.send("POST", "/api/v1/reports", nullable, true).statusCode()).isEqualTo(201);
    }

    @Test void archivedContentRemainsReportableButHiddenAndMissingTargetsDoNot() throws Exception {
        var board = board();
        var question = member.create(board);
        var reply = reply(question, other);
        jdbc.update("UPDATE board SET archived=true WHERE id=?", board);
        assertThat(report(member, "questionId", id(question).toString()).statusCode()).isEqualTo(201);
        assertThat(report(other, "replyId", id(reply).toString()).statusCode()).isEqualTo(201);
        jdbc.update("UPDATE reply SET visibility='HIDDEN' WHERE id=?", id(reply));
        assertThat(report(member, "replyId", id(reply).toString()).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE reply SET visibility='VISIBLE' WHERE id=?", id(reply));
        jdbc.update("UPDATE question SET visibility='HIDDEN' WHERE id=?", id(question));
        for (var actor : List.of(member, other, moderator, admin)) {
            assertThat(report(actor, "questionId", id(question).toString()).statusCode()).isEqualTo(404);
            assertThat(report(actor, "replyId", id(reply).toString()).statusCode()).isEqualTo(404);
        }
        for (var kind : List.of("questionId", "replyId")) {
            var missing = report(member, kind, UUID.randomUUID().toString());
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.body()).contains("CONTENT_NOT_FOUND");
        }
    }

    @Test void concurrentDuplicatesLeaveOneOpenReportPerTargetAndAllowIndependentReporters() throws Exception {
        var question = member.create(board());
        var reply = reply(question, other);
        for (var target : Map.of("questionId", id(question), "replyId", id(reply)).entrySet()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CountDownLatch(1);
                var first = executor.submit(() -> { start.await(); return report(member, target.getKey(), target.getValue().toString()); });
                var second = executor.submit(() -> { start.await(); return report(member, target.getKey(), target.getValue().toString()); });
                start.countDown();
                var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
                assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(201, 409);
                assertThat((a.statusCode() == 409 ? a : b).body()).contains("REPORT_ALREADY_OPEN");
            }
            assertThat(report(other, target.getKey(), target.getValue().toString()).statusCode()).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_report WHERE question_id=? OR reply_id=?",
                Integer.class, id(question), id(reply))).isEqualTo(4);
    }

    @Test void reportingRechecksVisibilityAfterWaitingForTheBoardLock() throws Exception {
        var board = board(); var question = member.create(board); var reply = reply(question, other);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT version FROM board WHERE id=? FOR UPDATE", Long.class, board);
                requests.add(executor.submit(() -> report(member, "questionId", id(question).toString()).statusCode()));
                requests.add(executor.submit(() -> report(member, "replyId", id(reply).toString()).statusCode()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (waiting < 2 && System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                            + " AND wait_event_type='Lock' AND query ILIKE '%board%'", Integer.class);
                    try { Thread.sleep(20); } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new RuntimeException(failure);
                    }
                }
                assertThat(waiting).as("Both report writes reached the coordinating lock").isGreaterThanOrEqualTo(2);
                jdbc.update("UPDATE question SET visibility='HIDDEN', version=version+1 WHERE id=?", id(question));
            });
            for (var request : requests) assertThat(request.get(15, TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_report WHERE question_id=? OR reply_id=?",
                Integer.class, id(question), id(reply))).isZero();
    }

    @Test void unicodeReasonBoundsAgreeBetweenHttpAndPostgres() throws Exception {
        var question = member.create(board());
        var response = member.send("POST", "/api/v1/reports", Map.of("questionId", id(question), "reason", "😀😀a"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT reason FROM content_report WHERE id=?", String.class,
                id(json.readTree(response.body())))).isEqualTo("😀😀a");
        assertThat(other.send("POST", "/api/v1/reports", Map.of("questionId", id(question), "reason", "😀".repeat(1000)), true)
                .statusCode()).isEqualTo(201);
        for (var invalid : List.of("😀😀", "😀".repeat(1001))) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,?)",
                    UUID.randomUUID(), id(admin.user), id(question), invalid)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test void reportServiceRequiresAuthenticationOutsideHttp() {
        assertThatThrownBy(() -> reportService.create(
                new com.lawrencenno.commonbeacon.moderation.CreateReportRequest(UUID.randomUUID(), null, "Valid reason"), null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test void databaseEnforcesTargetsForeignKeysUniqueOpenReportsAndResolutionMetadata() throws Exception {
        var question = member.create(board()); var reply = reply(question, other);
        UUID reporter = id(member.user);
        String insert = "INSERT INTO content_report(id,reporter_id,question_id,reply_id,reason) VALUES (?,?,?,?,?)";
        for (Object[] values : List.of(new Object[]{null, null}, new Object[]{id(question), id(reply)},
                new Object[]{UUID.randomUUID(), null}, new Object[]{null, UUID.randomUUID()})) {
            assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), reporter, values[0], values[1], "Valid reason"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), id(question), null, "Valid reason"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (Object[] target : List.of(new Object[]{id(question), null}, new Object[]{null, id(reply)})) {
            var reportId = UUID.randomUUID();
            jdbc.update(insert, reportId, reporter, target[0], target[1], "Valid reason");
            assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), reporter, target[0], target[1], "Other reason"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE content_report SET status='RESOLVED' WHERE id=?", reportId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE content_report SET status='RESOLVED', resolver_id=?, resolved_at=now(), resolution_decision='DISMISS', resolution_note='No violation' WHERE id=?", id(admin.user), reportId);
            String kind = target[0] == null ? "replyId" : "questionId";
            String targetId = (target[0] == null ? target[1] : target[0]).toString();
            assertThat(report(member, kind, targetId).statusCode()).isEqualTo(201);
        }
    }
}
