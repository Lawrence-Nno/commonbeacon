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
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=resolution-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationResolutionIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.lawrencenno.commonbeacon.moderation.ModerationResolutionService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.lawrencenno.commonbeacon.moderation.ModerationActionRepository actions;
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
                            + "&password=resolution-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
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

    String reviewPath(UUID id) { return "/api/v1/moderation/reports/" + id; }
    UUID reportId(JsonNode question) throws Exception {
        var response = report(member, "questionId", id(question).toString());
        assertThat(response.statusCode()).isEqualTo(201);
        return id(json.readTree(response.body()));
    }

    Map<String, Object> decision(UUID report, String decision) throws Exception {
        var detail = json.readTree(moderator.get(reviewPath(report)).body());
        var context = detail.get("context");
        var body = new HashMap<String, Object>();
        body.put("decision", decision); body.put("resolutionNote", "  Reviewed for community safety.  ");
        body.put("expectedVersion", detail.get("report").get("version").asLong());
        boolean reply = !context.get("reply").isNull();
        body.put("expectedTargetVersion", context.get(reply ? "reply" : "question").get("version").asLong());
        if (reply) body.put("expectedQuestionVersion", context.get("question").get("version").asLong());
        return body;
    }
    HttpResponse<String> resolve(Browser actor, UUID report, Map<String, ?> body) throws Exception {
        return actor.send("POST", reviewPath(report) + "/resolve", body, true);
    }
    int actionCount(UUID target) {
        return jdbc.queryForObject("SELECT count(*) FROM moderation_action WHERE question_id=? OR reply_id=?", Integer.class, target, target);
    }
    void accept(JsonNode question, JsonNode reply) throws Exception {
        var response = member.send("PUT", path(question) + "/accepted-reply", Map.of("replyId", id(reply), "expectedVersion", 0), true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    UUID replyReport(JsonNode reply, Browser actor) throws Exception {
        var response = report(actor, "replyId", id(reply).toString());
        assertThat(response.statusCode()).isEqualTo(201);
        return id(json.readTree(response.body()));
    }
    void assertOpen(UUID report) {
        assertThat(jdbc.queryForObject("SELECT status FROM content_report WHERE id=?", String.class, report)).isEqualTo("OPEN");
    }

    @Test void hidingAcceptedReplyOnArchivedBoardClearsSelectionAndOnlyResolvesSelectedReport() throws Exception {
        var board = board(); var question = member.create(board); var reply = reply(question, other);
        accept(question, reply);
        var report = replyReport(reply, member); var remaining = replyReport(reply, other);
        jdbc.update("UPDATE board SET archived=true WHERE id=?", board);
        var response = resolve(moderator, report, decision(report, "HIDE"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var result = json.readTree(response.body());
        assertThat(result.get("report").get("status").asText()).isEqualTo("RESOLVED");
        assertThat(result.get("report").get("version").asInt()).isEqualTo(1);
        assertThat(result.get("report").get("resolver").get("id")).isEqualTo(moderator.user.get("id"));
        assertThat(result.get("report").get("resolutionNote").asText()).isEqualTo("Reviewed for community safety.");
        assertThat(result.get("context").get("question").get("acceptedReplyId").isNull()).isTrue();
        assertThat(result.get("context").get("question").get("version").asInt()).isEqualTo(2);
        assertThat(result.get("context").get("reply").get("version").asInt()).isEqualTo(1);
        assertThat(result.get("context").get("reply").get("visibility").asText()).isEqualTo("HIDDEN");
        assertThat(result.get("availableDecisions").size()).isZero();
        assertThat(visitor.get(replyPath(reply)).statusCode()).isEqualTo(404);
        assertThat(json.readTree(visitor.get(path(question)).body()).get("solved").asBoolean()).isFalse();
        assertOpen(remaining); assertThat(actionCount(id(reply))).isEqualTo(1);
        assertThat(resolve(admin, remaining, decision(remaining, "ACKNOWLEDGE_HIDDEN")).statusCode()).isEqualTo(200);
        assertThat(actionCount(id(reply))).isEqualTo(1);
    }

    @Test void questionHideSuppressesPublicThreadButRetainsSelectionUntilAcceptedReplyIsHidden() throws Exception {
        var board = board(); var question = member.create(board); var reply = reply(question, other);
        accept(question, reply); var report = reportId(question); var childReport = replyReport(reply, member);
        assertThat(resolve(admin, report, decision(report, "HIDE")).statusCode()).isEqualTo(200);
        for (var path : List.of(path(question), repliesPath(question), replyPath(reply)))
            assertThat(visitor.get(path).statusCode()).isEqualTo(404);
        assertThat(visitor.get(listPath(board)).body()).doesNotContain(id(question).toString());
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, id(question))).isEqualTo(id(reply));
        assertThat(jdbc.queryForObject("SELECT visibility FROM reply WHERE id=?", String.class, id(reply))).isEqualTo("VISIBLE");
        var invalid = resolve(moderator, childReport, decision(childReport, "ACKNOWLEDGE_HIDDEN"));
        assertThat(invalid.statusCode()).isEqualTo(409); assertThat(invalid.body()).contains("MODERATION_STATE_CONFLICT");
        assertOpen(childReport);
        assertThat(resolve(moderator, childReport, decision(childReport, "HIDE")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, id(question))).isNull();
        assertThat(actionCount(id(question))).isEqualTo(1); assertThat(actionCount(id(reply))).isEqualTo(1);
    }

    @Test void unrelatedReplyHideLeavesQuestionVersionAndDifferentAcceptanceUnchanged() throws Exception {
        var question = member.create(board()); var selected = reply(question, other); var target = reply(question, member);
        accept(question, selected); var report = replyReport(target, other);
        assertThat(resolve(admin, report, decision(report, "HIDE")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT version FROM question WHERE id=?", Long.class, id(question))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, id(question))).isEqualTo(id(selected));
    }

    @Test void dismissalRecordsResolutionWithoutContentOrAuditChangesAndCannotResolveTwice() throws Exception {
        var question = member.create(board()); var report = reportId(question); var body = decision(report, "DISMISS");
        assertThat(resolve(moderator, report, body).statusCode()).isEqualTo(200);
        assertThat(visitor.get(path(question)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT version FROM question WHERE id=?", Long.class, id(question))).isZero();
        assertThat(actionCount(id(question))).isZero();
        var repeated = resolve(admin, report, body);
        assertThat(repeated.statusCode()).isEqualTo(409); assertThat(repeated.body()).contains("REPORT_ALREADY_RESOLVED");
    }

    @Test void validationRolesCsrfAndStaleVersionsLeaveStorageUntouched() throws Exception {
        var question = member.create(board()); var reply = reply(question, other); var report = replyReport(reply, member);
        var body = decision(report, "HIDE");
        assertThat(resolve(visitor, report, body).statusCode()).isEqualTo(401);
        assertThat(resolve(member, report, body).statusCode()).isEqualTo(403);
        assertThat(moderator.send("POST", reviewPath(report) + "/resolve", body, false).statusCode()).isEqualTo(403);
        for (var field : List.of("expectedVersion", "expectedTargetVersion", "expectedQuestionVersion")) {
            var stale = new HashMap<>(body); stale.put(field, 100);
            var response = resolve(moderator, report, stale);
            assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).contains("STALE_EDIT");
            stale.put(field, -1); assertThat(resolve(moderator, report, stale).statusCode()).isEqualTo(400);
            stale.remove(field); assertThat(resolve(moderator, report, stale).statusCode()).isEqualTo(400);
        }
        for (var note : List.of("    ", "tiny", "a".repeat(2001), "\uD83D\uDE00".repeat(1001))) {
            var bad = new HashMap<>(body); bad.put("resolutionNote", note);
            assertThat(resolve(moderator, report, bad).statusCode()).isEqualTo(400);
        }
        var forged = new HashMap<>(body); forged.put("resolverId", id(member.user));
        assertThat(resolve(moderator, report, forged).statusCode()).isEqualTo(400);
        forged = new HashMap<>(body); forged.put("decision", "DELETE");
        assertThat(resolve(moderator, report, forged).statusCode()).isEqualTo(400);
        assertThat(resolve(moderator, UUID.randomUUID(), body).statusCode()).isEqualTo(404);
        assertOpen(report); assertThat(actionCount(id(reply))).isZero();
        assertThat(visitor.get(replyPath(reply)).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> service.resolve(report, null, null)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test void failureAfterAuditInsertRollsBackVisibilityAcceptanceReportAndAudit() throws Exception {
        var question = member.create(board()); var reply = reply(question, other); accept(question, reply);
        var report = replyReport(reply, member);
        org.mockito.Mockito.doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Injected audit failure"); })
                .when(actions).appendHide(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(id(reply)), org.mockito.ArgumentMatchers.anyString());
        try { assertThat(resolve(moderator, report, decision(report, "HIDE")).statusCode()).isEqualTo(500); }
        finally { org.mockito.Mockito.reset(actions); }
        assertOpen(report); assertThat(actionCount(id(reply))).isZero();
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, id(question))).isEqualTo(id(reply));
        assertThat(jdbc.queryForObject("SELECT version FROM question WHERE id=?", Long.class, id(question))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM reply WHERE id=?", Long.class, id(reply))).isZero();
        assertThat(visitor.get(replyPath(reply)).statusCode()).isEqualTo(200);
    }

    @Test void simultaneousResolutionCommitsExactlyOneHide() throws Exception {
        var question = member.create(board()); var report = reportId(question); var body = decision(report, "HIDE");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return resolve(moderator, report, body); });
            var second = executor.submit(() -> { start.await(); return resolve(admin, report, body); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(), second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(actionCount(id(question))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM content_report WHERE id=?", Long.class, report)).isEqualTo(1);
    }

    @Test void auditDatabaseRejectsInvalidTargetsActionsActorsAndReasons() throws Exception {
        var question = member.create(board()); var reply = reply(question, other);
        String sql = "INSERT INTO moderation_action(id, actor_id, question_id, reply_id, action, reason) VALUES (?, ?, ?, ?, ?, ?)";
        for (Object[] row : List.of(
                new Object[]{UUID.randomUUID(), id(admin.user), null, null, "HIDE", "Valid note"},
                new Object[]{UUID.randomUUID(), id(admin.user), id(question), id(reply), "HIDE", "Valid note"},
                new Object[]{UUID.randomUUID(), UUID.randomUUID(), id(question), null, "HIDE", "Valid note"},
                new Object[]{UUID.randomUUID(), id(admin.user), id(question), null, "DELETE", "Valid note"},
                new Object[]{UUID.randomUUID(), id(admin.user), id(question), null, "HIDE", "tiny"},
                new Object[]{UUID.randomUUID(), id(admin.user), id(question), null, "HIDE", "\uD83D\uDE00".repeat(1001)}))
            assertThatThrownBy(() -> jdbc.update(sql, row)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(actionCount(id(question))).isZero();
    }
    @Test void waitingAuthorEditAndReplyCreationRecheckParentVisibilityAfterBoardLock() throws Exception {
        var boardId = board(); var question = member.create(boardId);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT version FROM board WHERE id=? FOR UPDATE", Long.class, boardId);
                requests.add(executor.submit(() -> member.send("PATCH", path(question), Map.of(
                        "title", "Edited after review", "body", "This write must not resurrect hidden content.", "expectedVersion", 0), true).statusCode()));
                requests.add(executor.submit(() -> other.send("POST", repliesPath(question), Map.of("body", "A reply waiting behind hiding."), true).statusCode()));
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
                assertThat(waiting).as("Both author writes reached the coordinating lock").isGreaterThanOrEqualTo(2);
                jdbc.update("UPDATE question SET visibility='HIDDEN', version=version+1 WHERE id=?", id(question));
            });
            for (var request : requests) assertThat(request.get(15, TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT title FROM question WHERE id=?", String.class, id(question))).isEqualTo(question.get("title").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=?", Integer.class, id(question))).isZero();
    }

}
