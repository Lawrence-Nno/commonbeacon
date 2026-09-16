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
class ReplyIT {
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

    @Test void secondMemberRepliesWithSessionAuthorAndPublicSafeFields() throws Exception {
        var question = member.create(board());
        var response = other.send("POST", repliesPath(question),
                Map.of("body", "  Helpful answer.  ", "authorId", member.user.get("id").asText(),
                        "visibility", "HIDDEN", "questionId", UUID.randomUUID().toString()), true);
        assertThat(response.statusCode()).isEqualTo(201);
        var created = json.readTree(response.body());
        assertThat(created.get("body").asText()).isEqualTo("Helpful answer.");
        assertThat(created.get("author").get("id")).isEqualTo(other.user.get("id"));
        assertThat(created.get("questionId")).isEqualTo(question.get("id"));
        assertThat(created.get("version").asLong()).isZero();
        var detail = visitor.get(replyPath(created));
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).doesNotContain("email", "password", "visibility");
        assertThat(json.readTree(detail.body())).isEqualTo(created);
        assertThat(visitor.get(repliesPath(question)).body()).contains(created.get("id").asText());
    }

    @Test void onlyReplyOwnerCanEditAndCannotMoveOrReassignReply() throws Exception {
        var question = member.create(board());
        var created = reply(question, other);
        for (var actor : List.of(member, moderator, admin)) {
            assertThat(actor.send("PATCH", replyPath(created), Map.of("body", "Forged edit", "expectedVersion", 0), true).statusCode()).isEqualTo(403);
        }
        var saved = other.send("PATCH", replyPath(created), Map.of("body", "  Revised answer.  ", "expectedVersion", 0,
                "authorId", member.user.get("id").asText(), "questionId", UUID.randomUUID().toString(), "visibility", "HIDDEN"), true);
        assertThat(saved.statusCode()).isEqualTo(200);
        var updated = json.readTree(saved.body());
        assertThat(updated.get("author")).isEqualTo(created.get("author"));
        assertThat(updated.get("questionId")).isEqualTo(created.get("questionId"));
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(updated.get("body").asText()).isEqualTo("Revised answer.");
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(other.send("PATCH", replyPath(created), Map.of("body", "Old draft", "expectedVersion", 0), true).statusCode()).isEqualTo(409);
    }

    @Test void hiddenParentOrReplyCannotBeReadOrEditedEvenByPrivilegedActors() throws Exception {
        var question = member.create(board());
        var visible = reply(question, other);
        var hidden = reply(question, other);
        jdbc.update("UPDATE reply SET visibility='HIDDEN', version=version+1 WHERE id=?", UUID.fromString(hidden.get("id").asText()));
        assertThat(visitor.get(replyPath(hidden)).statusCode()).isEqualTo(404);
        assertThat(other.send("PATCH", replyPath(hidden), Map.of("body", "Edit", "expectedVersion", 1), true).statusCode()).isEqualTo(404);
        var page = json.readTree(visitor.get(repliesPath(question)).body());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id")).isEqualTo(visible.get("id"));
        jdbc.update("UPDATE question SET visibility='HIDDEN', version=version+1 WHERE id=?", UUID.fromString(question.get("id").asText()));
        for (var actor : List.of(visitor, member, other, admin)) {
            assertThat(actor.get(repliesPath(question)).statusCode()).isEqualTo(404);
            assertThat(actor.get(replyPath(visible)).statusCode()).isEqualTo(404);
        }
        assertThat(other.send("POST", repliesPath(question), Map.of("body", "New reply"), true).statusCode()).isEqualTo(404);
        assertThat(other.send("PATCH", replyPath(visible), Map.of("body", "Edit", "expectedVersion", 0), true).statusCode()).isEqualTo(404);
    }

    @Test void archivedBoardKeepsRepliesReadableButRejectsCreationAndOwnerEdits() throws Exception {
        var board = board();
        var question = member.create(board);
        var created = reply(question, other);
        assertThat(admin.send("PATCH", "/api/v1/boards/" + board, Map.of("archived", true, "expectedVersion", 0), true).statusCode()).isEqualTo(200);
        assertThat(visitor.get(repliesPath(question)).statusCode()).isEqualTo(200);
        assertThat(visitor.get(replyPath(created)).statusCode()).isEqualTo(200);
        for (var actor : List.of(member, other, moderator, admin)) {
            assertThat(actor.send("POST", repliesPath(question), Map.of("body", "Reply"), true).statusCode()).isEqualTo(409);
        }
        assertThat(other.send("PATCH", replyPath(created), Map.of("body", "Edit", "expectedVersion", 0), true).statusCode()).isEqualTo(409);
    }

    @Test void validationCsrfAnonymousAndMissingRecordsAreControlled() throws Exception {
        var question = member.create(board());
        for (String body : List.of("", " ", "x".repeat(20001))) {
            var response = other.send("POST", repliesPath(question), Map.of("body", body), true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("fieldErrors", "body");
        }
        assertThat(other.send("POST", repliesPath(question), Map.of("body", "x"), true).statusCode()).isEqualTo(201);
        assertThat(other.send("POST", repliesPath(question), Map.of("body", "x".repeat(20000)), true).statusCode()).isEqualTo(201);
        var created = reply(question, other);
        assertThat(other.send("PATCH", replyPath(created), Map.of("body", "Text"), true).statusCode()).isEqualTo(400);
        assertThat(other.send("PATCH", replyPath(created), Map.of("body", "Text", "expectedVersion", -1), true).statusCode()).isEqualTo(400);
        assertThat(other.send("POST", repliesPath(question), Map.of("body", "Text"), false).statusCode()).isEqualTo(403);
        assertThat(other.send("PATCH", replyPath(created), Map.of("body", "Text", "expectedVersion", 0), false).statusCode()).isEqualTo(403);
        assertThat(visitor.send("POST", repliesPath(question), Map.of("body", "Text"), true).statusCode()).isEqualTo(401);
        assertThat(visitor.send("PATCH", replyPath(created), Map.of("body", "Text", "expectedVersion", 0), true).statusCode()).isEqualTo(401);
        assertThat(visitor.get("/api/v1/replies/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(visitor.get("/api/v1/replies/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        String missing = "/api/v1/questions/" + UUID.randomUUID() + "/replies";
        assertThat(visitor.get(missing).statusCode()).isEqualTo(404);
        assertThat(other.send("POST", missing, Map.of("body", "Text"), true).statusCode()).isEqualTo(404);
    }

    @Test void pagesAreOldestFirstWithStableTiesAndExcludeHiddenReplies() throws Exception {
        var question = member.create(board());
        for (int i = 1; i <= 4; i++) {
            jdbc.update("""
                    INSERT INTO reply (id,question_id,author_id,body,visibility,created_at)
                    VALUES (?,?,?,?,?,'2026-01-01T00:00:00Z')
                    """, UUID.fromString("10000000-0000-0000-0000-00000000000" + i),
                    UUID.fromString(question.get("id").asText()), UUID.fromString(other.user.get("id").asText()),
                    "Reply " + i, i == 4 ? "HIDDEN" : "VISIBLE");
        }
        var first = json.readTree(visitor.get(repliesPath(question) + "?size=2").body());
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("items").get(0).get("body").asText()).isEqualTo("Reply 1");
        assertThat(first.get("items").get(1).get("body").asText()).isEqualTo("Reply 2");
        var second = json.readTree(visitor.get(repliesPath(question) + "?page=1&size=2").body());
        assertThat(second.get("items").get(0).get("body").asText()).isEqualTo("Reply 3");
        assertThat(json.readTree(visitor.get(repliesPath(question)).body()).get("size").asInt()).isEqualTo(20);
        assertThat(json.readTree(visitor.get(repliesPath(question) + "?page=99").body()).get("items").isEmpty()).isTrue();
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=bad", "?page=2147483647&size=100")) {
            assertThat(visitor.get(repliesPath(question) + query).statusCode()).isEqualTo(400);
        }
    }

    @Test void concurrentEditsCannotSilentlyOverwriteEachOther() throws Exception {
        var created = reply(member.create(board()), other);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Integer> update = () -> {
                start.await();
                return other.send("PATCH", replyPath(created), Map.of("body", "Edit " + UUID.randomUUID(), "expectedVersion", 0), true).statusCode();
            };
            var first = executor.submit(update); var second = executor.submit(update); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(json.readTree(visitor.get(replyPath(created)).body()).get("version").asLong()).isEqualTo(1);
        }
    }

    @Test void writesWaitingForArchiveRecheckTheBoard() throws Exception {
        var board = board();
        var question = member.create(board());
        // Use the question's actual board for the holding transaction.
        board = UUID.fromString(question.get("board").get("id").asText());
        var created = reply(question, other);
        UUID heldBoard = board;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE board SET archived=true, version=version+1 WHERE id=?", heldBoard);
                attempts.add(executor.submit(() -> other.send("POST", repliesPath(question), Map.of("body", "Waiting reply"), true).statusCode()));
                attempts.add(executor.submit(() -> other.send("PATCH", replyPath(created), Map.of("body", "Waiting edit", "expectedVersion", 0), true).statusCode()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (waiting < 2 && System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%board%'", Integer.class);
                    try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
                }
                assertThat(waiting).isGreaterThanOrEqualTo(2);
            });
            for (var attempt : attempts) assertThat(attempt.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        }
    }

    @Test void replyQuestionForeignKeyIsEnforcedByPostgres() {
        String sql = "INSERT INTO reply (id,question_id,author_id,body,visibility) VALUES (?,?,?,?,?)";
        UUID author = UUID.fromString(other.user.get("id").asText());
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), UUID.randomUUID(), author, "Text", "VISIBLE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
