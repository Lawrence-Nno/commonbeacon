package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.question.CreateQuestionRequest;
import com.lawrencenno.commonbeacon.question.QuestionService;
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
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=reply-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcceptedReplyIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QuestionService service;
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
                            + "&password=reply-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
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
    Map<String, Object> edit(long version) { return Map.of("title", "Updated question title", "body", "Here are the updated details.", "expectedVersion", version); }
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

    String acceptedPath(JsonNode question) { return path(question) + "/accepted-reply"; }
    HttpResponse<String> accept(Browser actor, JsonNode question, JsonNode reply, long version) throws Exception {
        return actor.send("PUT", acceptedPath(question), Map.of("replyId", reply.get("id").asText(), "expectedVersion", version), true);
    }
    HttpResponse<String> clear(Browser actor, JsonNode question, long version) throws Exception {
        return actor.send("DELETE", acceptedPath(question) + "?expectedVersion=" + version, Map.of(), true);
    }

    @Test void authorAcceptsReplacesWithOwnReplyAndClearsWithPersistedVersions() throws Exception {
        var question = member.create(board());
        var first = reply(question, other);
        var own = reply(question, member);
        var selected = accept(member, question, first, 0);
        assertThat(selected.statusCode()).isEqualTo(200);
        var detail = json.readTree(selected.body());
        assertThat(detail.get("solved").asBoolean()).isTrue();
        assertThat(detail.get("acceptedReply")).isEqualTo(first);
        assertThat(detail.get("version").asLong()).isEqualTo(1);
        assertThat(selected.body()).doesNotContain("password", "email", "visibility");
        assertThat(json.readTree(visitor.get(path(question)).body())).isEqualTo(detail);
        assertThat(accept(member, question, own, 1).statusCode()).isEqualTo(200);
        assertThat(json.readTree(visitor.get(path(question)).body()).get("acceptedReply")).isEqualTo(own);
        assertThat(clear(member, question, 1).statusCode()).isEqualTo(409);
        var cleared = clear(member, question, 2);
        assertThat(cleared.statusCode()).isEqualTo(200);
        var after = json.readTree(visitor.get(path(question)).body());
        assertThat(after.get("acceptedReply").isNull()).isTrue();
        assertThat(after.get("solved").asBoolean()).isFalse();
        assertThat(after.get("version").asLong()).isEqualTo(3);
    }

    @Test void onlyQuestionOwnerCanSelectOrClearRegardlessOfRole() throws Exception {
        var question = member.create(board());
        var answer = reply(question, other);
        for (var actor : List.of(other, moderator, admin)) {
            assertThat(accept(actor, question, answer, 0).statusCode()).isEqualTo(403);
            assertThat(clear(actor, question, 0).statusCode()).isEqualTo(403);
        }
        assertThat(accept(visitor, question, answer, 0).statusCode()).isEqualTo(401);
        assertThat(clear(visitor, question, 0).statusCode()).isEqualTo(401);
        assertThat(accept(member, question, answer, 0).statusCode()).isEqualTo(200);
        for (var actor : List.of(other, moderator, admin)) {
            assertThat(clear(actor, question, 1).statusCode()).isEqualTo(403);
        }
    }

    @Test void rejectsCrossQuestionSelectionThroughHttpAndDirectSql() throws Exception {
        var question = member.create(board());
        var foreignReply = reply(member.create(board()), other);
        assertThat(accept(member, question, foreignReply, 0).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?",
                UUID.fromString(foreignReply.get("id").asText()), UUID.fromString(question.get("id").asText())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(json.readTree(visitor.get(path(question)).body()).get("solved").asBoolean()).isFalse();
    }

    @Test void rejectsHiddenRepliesHiddenQuestionsAndArchivedChanges() throws Exception {
        var board = board();
        var question = member.create(board);
        var hidden = reply(question, other);
        jdbc.update("UPDATE reply SET visibility='HIDDEN' WHERE id=?", UUID.fromString(hidden.get("id").asText()));
        assertThat(accept(member, question, hidden, 0).statusCode()).isEqualTo(404);
        var answer = reply(question, other);
        assertThat(accept(member, question, answer, 0).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE board SET archived=true WHERE id=?", board);
        assertThat(accept(member, question, answer, 1).statusCode()).isEqualTo(409);
        assertThat(clear(member, question, 1).statusCode()).isEqualTo(409);
        assertThat(json.readTree(visitor.get(path(question)).body()).get("acceptedReply")).isEqualTo(answer);
        jdbc.update("UPDATE question SET visibility='HIDDEN' WHERE id=?", UUID.fromString(question.get("id").asText()));
        assertThat(accept(member, question, answer, 1).statusCode()).isEqualTo(404);
        assertThat(clear(member, question, 1).statusCode()).isEqualTo(404);
        assertThat(visitor.get(path(question)).statusCode()).isEqualTo(404);
    }

    @Test void validatesVersionsReplyIdsAndCsrf() throws Exception {
        var question = member.create(board());
        var answer = reply(question, other);
        var endpoint = acceptedPath(question);
        for (Map<String, ?> input : List.<Map<String, ?>>of(Map.of(), Map.of("replyId", answer.get("id").asText()),
                Map.of("expectedVersion", 0), Map.of("replyId", "invalid", "expectedVersion", 0),
                Map.of("replyId", answer.get("id").asText(), "expectedVersion", -1))) {
            assertThat(member.send("PUT", endpoint, input, true).statusCode()).isEqualTo(400);
        }
        assertThat(member.send("PUT", endpoint, Map.of("replyId", UUID.randomUUID().toString(), "expectedVersion", 0), true).statusCode()).isEqualTo(404);
        assertThat(member.send("PUT", endpoint, Map.of("replyId", answer.get("id").asText(), "expectedVersion", 0), false).statusCode()).isEqualTo(403);
        assertThat(member.send("DELETE", endpoint + "?expectedVersion=0", Map.of(), false).statusCode()).isEqualTo(403);
        assertThat(clear(member, question, -1).statusCode()).isEqualTo(400);
        assertThat(member.send("DELETE", endpoint, Map.of(), true).statusCode()).isEqualTo(400);
        assertThat(accept(member, question, answer, 9).statusCode()).isEqualTo(409);
    }

    @Test void concurrentSelectionsAndClearCannotBypassExpectedVersion() throws Exception {
        var question = member.create(board());
        var firstReply = reply(question, other);
        var secondReply = reply(question, member);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return accept(member, question, firstReply, 0).statusCode(); });
            var second = executor.submit(() -> { start.await(); return accept(member, question, secondReply, 0).statusCode(); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            var saved = json.readTree(visitor.get(path(question)).body());
            assertThat(saved.get("version").asLong()).isEqualTo(1);
            assertThat(saved.get("acceptedReply").get("id").asText()).isIn(firstReply.get("id").asText(), secondReply.get("id").asText());
            var next = new CountDownLatch(1);
            var replace = executor.submit(() -> { next.await(); return accept(member, question, firstReply, 1).statusCode(); });
            var clear = executor.submit(() -> { next.await(); return clear(member, question, 1).statusCode(); });
            next.countDown();
            assertThat(List.of(replace.get(20, TimeUnit.SECONDS), clear.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(json.readTree(visitor.get(path(question)).body()).get("version").asLong()).isEqualTo(2);
        }
    }

    @Test void filteredCountsAndPagesExcludeHiddenQuestionsAndFollowSelectionChanges() throws Exception {
        var board = board();
        var first = member.create(board);
        var second = member.create(board);
        var third = member.create(board);
        var hidden = member.create(board);
        accept(member, first, reply(first, other), 0);
        accept(member, second, reply(second, member), 0);
        accept(member, hidden, reply(hidden, other), 0);
        jdbc.update("UPDATE question SET visibility='HIDDEN' WHERE id=?", UUID.fromString(hidden.get("id").asText()));
        var solved = json.readTree(visitor.get(listPath(board) + "?status=solved&size=1").body());
        assertThat(solved.get("totalElements").asLong()).isEqualTo(2);
        assertThat(solved.get("totalPages").asInt()).isEqualTo(2);
        assertThat(solved.get("items").get(0).get("solved").asBoolean()).isTrue();
        var next = json.readTree(visitor.get(listPath(board) + "?status=solved&size=1&page=1").body());
        assertThat(next.get("items").get(0).get("id")).isNotEqualTo(solved.get("items").get(0).get("id"));
        var unanswered = json.readTree(visitor.get(listPath(board) + "?status=unanswered").body());
        assertThat(unanswered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(unanswered.get("items").get(0).get("id")).isEqualTo(third.get("id"));
        assertThat(json.readTree(visitor.get(listPath(board)).body()).get("totalElements").asLong()).isEqualTo(3);
        clear(member, first, 1);
        assertThat(json.readTree(visitor.get(listPath(board) + "?status=unanswered").body()).get("totalElements").asLong()).isEqualTo(2);
        assertThat(visitor.get(listPath(board) + "?status=unknown").statusCode()).isEqualTo(400);
    }

    @Test void hiddenAcceptedReplyIsExcludedFromAllPublicSolutionViews() throws Exception {
        var board = board();
        var question = member.create(board);
        var answer = reply(question, other);
        assertThat(accept(member, question, answer, 0).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE reply SET visibility='HIDDEN' WHERE id=?", UUID.fromString(answer.get("id").asText()));
        assertThat(visitor.get(replyPath(answer)).statusCode()).isEqualTo(404);
        var detail = json.readTree(visitor.get(path(question)).body());
        assertThat(detail.get("acceptedReply").isNull()).isTrue();
        assertThat(detail.get("solved").asBoolean()).isFalse();
        var all = json.readTree(visitor.get(listPath(board)).body());
        assertThat(all.get("items").get(0).get("solved").asBoolean()).isFalse();
        assertThat(json.readTree(visitor.get(listPath(board) + "?status=solved").body()).get("totalElements").asInt()).isZero();
        assertThat(json.readTree(visitor.get(listPath(board) + "?status=unanswered").body()).get("totalElements").asInt()).isEqualTo(1);
        // Read protection must not mutate storage. Moderation will clear this atomically.
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class,
                UUID.fromString(question.get("id").asText()))).isEqualTo(UUID.fromString(answer.get("id").asText()));
        var edited = member.send("PATCH", path(question), edit(1), true);
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(json.readTree(edited.body()).get("acceptedReply").isNull()).isTrue();
        assertThat(clear(member, question, 2).statusCode()).isEqualTo(200);
    }

    @Test void concurrentQuestionEditAndAcceptancePreserveTheWinningChange() throws Exception {
        var question = member.create(board());
        var answer = reply(question, other);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var selection = executor.submit(() -> { start.await(); return accept(member, question, answer, 0).statusCode(); });
            var editing = executor.submit(() -> { start.await(); return member.send("PATCH", path(question), edit(0), true).statusCode(); });
            start.countDown();
            int selected = selection.get(20, TimeUnit.SECONDS);
            int edited = editing.get(20, TimeUnit.SECONDS);
            assertThat(List.of(selected, edited)).containsExactlyInAnyOrder(200, 409);
            var saved = json.readTree(visitor.get(path(question)).body());
            assertThat(saved.get("version").asLong()).isEqualTo(1);
            assertThat(saved.get("solved").asBoolean()).isEqualTo(selected == 200);
            assertThat(saved.get("title").asText()).isEqualTo(edited == 200 ? "Updated question title" : question.get("title").asText());
            if (selected == 200) assertThat(saved.get("acceptedReply").get("id")).isEqualTo(answer.get("id"));
        }
    }

    @Test void acceptanceWaitingBehindArchiveRechecksBoardState() throws Exception {
        var board = board();
        var question = member.create(board);
        var answer = reply(question, other);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE board SET archived=true, version=version+1 WHERE id=?", board);
                attempts.add(executor.submit(() -> accept(member, question, answer, 0).statusCode()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    Integer waiting = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()", Integer.class);
                    if (waiting != null && waiting > 0) { blocked = true; break; }
                    try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
                }
                assertThat(blocked).as("Acceptance must wait for the archive transaction").isTrue();
            });
            assertThat(attempts.getFirst().get(20, TimeUnit.SECONDS)).isEqualTo(409);
            var saved = json.readTree(visitor.get(path(question)).body());
            assertThat(saved.get("acceptedReply").isNull()).isTrue();
            assertThat(saved.get("version").asLong()).isZero();
        }
    }
}
