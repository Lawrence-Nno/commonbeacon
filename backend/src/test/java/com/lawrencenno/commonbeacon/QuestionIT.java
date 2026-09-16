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
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=question-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionIT {
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
                            + "&password=question-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
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

    @Test void authorComesFromSessionAndPublicResponsesContainOnlySafeFields() throws Exception {
        var board = board();
        var forged = new HashMap<>(draft());
        forged.put("authorId", other.user.get("id").asText());
        forged.put("visibility", "HIDDEN");
        forged.put("version", 900);
        var response = member.send("POST", listPath(board), forged, true);
        assertThat(response.statusCode()).isEqualTo(201);
        var created = json.readTree(response.body());
        assertThat(created.get("title").asText()).isEqualTo("How do I get started?");
        assertThat(created.get("body").asText()).isEqualTo("I would like help with the first steps.");
        assertThat(created.get("author").get("id")).isEqualTo(member.user.get("id"));
        assertThat(created.get("version").asLong()).isZero();
        var read = visitor.get(path(created));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).doesNotContain("email", "password", "visibility", "MEMBER");
        assertThat(json.readTree(read.body()).get("body")).isEqualTo(created.get("body"));
    }

    @Test void onlyOwnerCanEditEvenWhenOtherActorsArePrivileged() throws Exception {
        var created = member.create(board());
        for (var actor : List.of(other, moderator, admin)) {
            assertThat(actor.send("PATCH", path(created), edit(0), true).statusCode()).isEqualTo(403);
        }
        var forged = new HashMap<>(edit(0));
        forged.put("authorId", other.user.get("id").asText());
        forged.put("boardId", board().toString());
        forged.put("visibility", "HIDDEN");
        var saved = member.send("PATCH", path(created), forged, true);
        assertThat(saved.statusCode()).isEqualTo(200);
        var updated = json.readTree(saved.body());
        assertThat(updated.get("author")).isEqualTo(created.get("author"));
        assertThat(updated.get("board")).isEqualTo(created.get("board"));
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(updated.get("updatedAt")).isNotEqualTo(created.get("updatedAt"));
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(member.send("PATCH", path(created), edit(0), true).statusCode()).isEqualTo(409);
        assertThat(json.readTree(visitor.get(path(created)).body()).get("title").asText()).isEqualTo("Updated question title");
    }

    @Test void archivedBoardsRejectNewQuestionsAndAllOwnerEditsButRemainReadable() throws Exception {
        var board = board();
        var created = member.create(board);
        var administratorQuestion = admin.create(board);
        assertThat(admin.send("PATCH", "/api/v1/boards/" + board,
                Map.of("expectedVersion", 0, "archived", true), true).statusCode()).isEqualTo(200);
        for (var actor : List.of(member, moderator, admin)) {
            var result = actor.send("POST", listPath(board), draft(), true);
            assertThat(result.statusCode()).isEqualTo(409);
            assertThat(result.body()).contains("BOARD_ARCHIVED");
        }
        assertThat(member.send("PATCH", path(created), edit(0), true).statusCode()).isEqualTo(409);
        assertThat(admin.send("PATCH", path(administratorQuestion), edit(0), true).statusCode()).isEqualTo(409);
        assertThat(visitor.get(path(created)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(visitor.get(listPath(board)).body()).get("totalElements").asLong()).isEqualTo(2);
    }

    @Test void hiddenQuestionsAreAbsentFromListsDetailsAndOwnerEditing() throws Exception {
        var board = board();
        var visible = member.create(board);
        var hidden = member.create(board);
        jdbc.update("UPDATE question SET visibility='HIDDEN', version=version+1 WHERE id=?",
                UUID.fromString(hidden.get("id").asText()));
        for (var actor : List.of(visitor, member, admin)) {
            assertThat(actor.get(path(hidden)).statusCode()).isEqualTo(404);
            var list = actor.get(listPath(board));
            assertThat(list.body()).contains(visible.get("id").asText()).doesNotContain(hidden.get("id").asText());
            assertThat(json.readTree(list.body()).get("totalElements").asLong()).isEqualTo(1);
        }
        assertThat(member.send("PATCH", path(hidden), edit(1), true).statusCode()).isEqualTo(404);
    }

    @Test void fieldBoundsAndRequiredEditVersionsAreValidatedAfterTrimming() throws Exception {
        var board = board();
        for (var input : List.of(Map.of("title", " four ", "body", " ninechars "),
                Map.of("title", "x".repeat(201), "body", "x".repeat(20001)),
                Map.of("title", " ", "body", " "))) {
            var invalid = member.send("POST", listPath(board), input, true);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("fieldErrors", "title", "body");
        }
        var maximum = member.send("POST", listPath(board),
                Map.of("title", "x".repeat(200), "body", "x".repeat(20000)), true);
        assertThat(maximum.statusCode()).isEqualTo(201);
        var created = json.readTree(maximum.body());
        assertThat(member.send("PATCH", path(created), draft(), true).statusCode()).isEqualTo(400);
        assertThat(member.send("PATCH", path(created), edit(-1), true).statusCode()).isEqualTo(400);
    }

    @Test void paginationIsBoundedStableAndCountsOnlyVisibleQuestions() throws Exception {
        var board = board();
        for (int i = 1; i <= 4; i++) {
            jdbc.update("""
                    INSERT INTO question (id, board_id, author_id, title, body, visibility, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, '2026-01-01T00:00:00Z')
                    """, UUID.fromString("00000000-0000-0000-0000-00000000000" + i), board,
                    UUID.fromString(member.user.get("id").asText()), "Question " + i, "Useful question details.",
                    i == 4 ? "HIDDEN" : "VISIBLE");
        }
        var first = json.readTree(visitor.get(listPath(board) + "?page=0&size=2").body());
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("items").get(0).get("title").asText()).isEqualTo("Question 3");
        assertThat(first.get("items").get(1).get("title").asText()).isEqualTo("Question 2");
        assertThat(first.get("items").get(0).has("body")).isFalse();
        var second = json.readTree(visitor.get(listPath(board) + "?page=1&size=2").body());
        assertThat(second.get("items").size()).isEqualTo(1);
        assertThat(second.get("items").get(0).get("title").asText()).isEqualTo("Question 1");
        var defaults = json.readTree(visitor.get(listPath(board)).body());
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(json.readTree(visitor.get(listPath(board) + "?page=10").body()).get("items").isEmpty()).isTrue();
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=no", "?page=2147483647&size=100")) {
            assertThat(visitor.get(listPath(board) + query).statusCode()).isEqualTo(400);
        }
    }

    @Test void missingRecordsMalformedIdsAndUnauthenticatedMutationsReturnControlledErrors() throws Exception {
        var board = board();
        var created = member.create(board);
        assertThat(visitor.get(listPath(UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(visitor.get("/api/v1/questions/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(visitor.get("/api/v1/questions/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(member.send("POST", listPath(UUID.randomUUID()), draft(), true).statusCode()).isEqualTo(404);
        assertThat(visitor.send("POST", listPath(board), draft(), true).statusCode()).isEqualTo(401);
        assertThat(visitor.send("PATCH", path(created), edit(0), true).statusCode()).isEqualTo(401);
        assertThat(member.send("POST", listPath(board), draft(), false).statusCode()).isEqualTo(403);
        assertThat(member.send("PATCH", path(created), edit(0), false).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> service.create(board, new CreateQuestionRequest("A valid title", "Valid question details."), null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test void concurrentOwnerEditsReturnOneSuccessAndOneConflict() throws Exception {
        var created = member.create(board());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Integer> update = () -> {
                start.await();
                return member.send("PATCH", path(created),
                        Map.of("title", "Edit " + UUID.randomUUID(), "body", "Concurrent edit details.", "expectedVersion", 0), true).statusCode();
            };
            var first = executor.submit(update);
            var second = executor.submit(update);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(json.readTree(visitor.get(path(created)).body()).get("version").asLong()).isEqualTo(1);
        }
    }

    @Test void writesWaitingForAnArchiveTransactionRecheckTheClosedBoard() throws Exception {
        var board = board();
        var created = member.create(board);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE board SET archived=true, version=version+1 WHERE id=?", board);
                attempts.add(executor.submit(() -> member.send("POST", listPath(board), draft(), true).statusCode()));
                attempts.add(executor.submit(() -> member.send("PATCH", path(created), edit(0), true).statusCode()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (waiting < 2 && System.nanoTime() < deadline) {
                    // Statistics are cached within the holding transaction; refresh before observing waiters.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                            + " AND wait_event_type='Lock' AND query ILIKE '%board%'", Integer.class);
                    try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
                }
                assertThat(waiting).as("Both HTTP writes wait on the board lock").isGreaterThanOrEqualTo(2);
            });
            for (var attempt : attempts) assertThat(attempt.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE board_id=?", Integer.class, board)).isEqualTo(1);
        assertThat(json.readTree(visitor.get(path(created)).body()).get("version").asLong()).isZero();
    }

    @Test void databaseEnforcesBoardAuthorAndVisibilityConstraints() {
        UUID board = board();
        UUID author = UUID.fromString(member.user.get("id").asText());
        String sql = "INSERT INTO question (id,board_id,author_id,title,body,visibility) VALUES (?,?,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), UUID.randomUUID(), author,
                "Valid title", "Valid question body.", "VISIBLE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), board, UUID.randomUUID(),
                "Valid title", "Valid question body.", "VISIBLE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), board, author,
                "Valid title", "Valid question body.", "PRIVATE")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
