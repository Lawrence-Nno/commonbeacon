package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.DemoDataSeeder;
import com.lawrencenno.commonbeacon.board.BoardService;
import com.lawrencenno.commonbeacon.board.CreateBoardRequest;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=board-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
class BoardIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataSeeder seeder;
    @Autowired BoardService service;
    static final String PASSWORD = "board-test-password-42";

    class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf;
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
                            + "&password=" + PASSWORD)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            token();
        }
        JsonNode create() throws Exception {
            var response = send("POST", "/api/v1/boards", data(slug()), true);
            assertThat(response.statusCode()).isEqualTo(201);
            var board = json.readTree(response.body());
            assertThat(response.headers().firstValue("location")).contains("/api/v1/boards/" + board.get("id").asText());
            return board;
        }
        public void close() { client.close(); }
    }
    String slug() { return "test-" + UUID.randomUUID(); }
    Map<String, Object> data(String slug) { return Map.of("slug", slug, "name", " Test board ", "description", " Useful help "); }

    @Test void visitorsCanReadBoardsAndReceiveControlledMissingAndMalformedErrors() throws Exception {
        try (var browser = new Browser()) {
            var list = browser.get("/api/v1/boards");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).contains("getting-started", "product-help").doesNotContain("password", "email");
            assertThat(browser.get("/api/v1/boards/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
            assertThat(browser.get("/api/v1/boards/not-a-uuid").statusCode()).isEqualTo(400);
        }
    }

    @Test void anonymousAndMissingCsrfMutationsAreRejected() throws Exception {
        try (var browser = new Browser()) {
            assertThat(browser.send("POST", "/api/v1/boards", data(slug()), false).statusCode()).isEqualTo(403);
            browser.token();
            assertThat(browser.send("POST", "/api/v1/boards", data(slug()), true).statusCode()).isEqualTo(401);
            assertThat(browser.send("PATCH", "/api/v1/boards/" + UUID.randomUUID(),
                    Map.of("expectedVersion", 0, "archived", true), true).statusCode()).isEqualTo(401);
        }
        assertThatThrownBy(() -> service.create(new CreateBoardRequest(slug(), "Name", "Description")))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test void membersAndModeratorsCannotCreateEditOrArchiveThroughDirectRequests() throws Exception {
        UUID boardId = jdbc.queryForObject("SELECT id FROM board WHERE slug='getting-started'", UUID.class);
        for (String email : List.of("alex.member@example.test", "morgan.moderator@example.test")) {
            try (var browser = new Browser()) {
                browser.login(email);
                assertThat(browser.send("POST", "/api/v1/boards", data(slug()), true).statusCode()).isEqualTo(403);
                assertThat(browser.send("PATCH", "/api/v1/boards/" + boardId,
                        Map.of("expectedVersion", 0, "name", "Forged name"), true).statusCode()).isEqualTo(403);
                assertThat(browser.send("PATCH", "/api/v1/boards/" + boardId,
                        Map.of("expectedVersion", 0, "archived", true), true).statusCode()).isEqualTo(403);
            }
        }
    }

    @Test void administratorCanCreateEditArchiveAndReopenWithoutRemovingPublicAccess() throws Exception {
        try (var browser = new Browser(); var visitor = new Browser()) {
            browser.login("avery.admin@example.test");
            var board = browser.create();
            String path = "/api/v1/boards/" + board.get("id").asText();
            assertThat(board.get("name").asText()).isEqualTo("Test board");
            assertThat(board.get("description").asText()).isEqualTo("Useful help");
            assertThat(browser.send("PATCH", path, Map.of("expectedVersion", 0, "name", "Edited board",
                    "archived", true), false).statusCode()).isEqualTo(403);
            var archived = browser.send("PATCH", path, Map.of("expectedVersion", 0, "name", "Edited board",
                    "archived", true), true);
            assertThat(archived.statusCode()).isEqualTo(200);
            assertThat(json.readTree(archived.body()).get("version").asLong()).isEqualTo(1);
            var publicBoard = visitor.get(path);
            assertThat(publicBoard.statusCode()).isEqualTo(200);
            assertThat(json.readTree(publicBoard.body()).get("archived").asBoolean()).isTrue();
            assertThat(visitor.get("/api/v1/boards").body()).contains(board.get("id").asText());
            assertThat(browser.send("PATCH", path, Map.of("expectedVersion", 0, "name", "Stale overwrite"), true)
                    .statusCode()).isEqualTo(409);
            var reopened = browser.send("PATCH", path, Map.of("expectedVersion", 1, "archived", false), true);
            assertThat(reopened.statusCode()).isEqualTo(200);
            assertThat(json.readTree(reopened.body()).get("archived").asBoolean()).isFalse();
        }
    }

    @Test void duplicateSlugsAndInvalidFieldsReturnUsefulErrors() throws Exception {
        try (var browser = new Browser()) {
            browser.login("avery.admin@example.test");
            var board = browser.create();
            var duplicate = browser.send("POST", "/api/v1/boards", data(board.get("slug").asText()), true);
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(duplicate.body()).contains("BOARD_CONFLICT").doesNotContain("email", "SQL");
            var second = browser.create();
            assertThat(browser.send("PATCH", "/api/v1/boards/" + second.get("id").asText(),
                    Map.of("slug", board.get("slug").asText(), "expectedVersion", 0), true).statusCode()).isEqualTo(409);
            var invalid = browser.send("POST", "/api/v1/boards",
                    Map.of("slug", "Bad Slug", "name", " ", "description", "x".repeat(2001)), true);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("fieldErrors", "slug", "name", "description");
            assertThat(browser.send("PATCH", "/api/v1/boards/" + board.get("id").asText(),
                    Map.of("name", "No version"), true).statusCode()).isEqualTo(400);
            assertThat(browser.send("PATCH", "/api/v1/boards/" + board.get("id").asText(),
                    Map.of("name", " ", "expectedVersion", 0), true).statusCode()).isEqualTo(400);
        }
    }

    @Test void concurrentUpdatesCannotSilentlyOverwriteEachOther() throws Exception {
        try (var browser = new Browser(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            browser.login("avery.admin@example.test");
            var board = browser.create();
            var start = new CountDownLatch(1);
            Callable<Integer> update = () -> {
                start.await();
                return browser.send("PATCH", "/api/v1/boards/" + board.get("id").asText(),
                        Map.of("name", "Edit " + UUID.randomUUID(), "expectedVersion", 0), true).statusCode();
            };
            var first = executor.submit(update);
            var second = executor.submit(update);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test void reseedingPreservesExistingCredentialsRolesAndEditedBoards() {
        String changedHash = "test-only-changed-hash";
        jdbc.update("UPDATE app_user SET password_hash=?, role='MODERATOR' WHERE email='sam.member@example.test'", changedHash);
        UUID id = jdbc.queryForObject("SELECT id FROM board WHERE slug='product-help'", UUID.class);
        jdbc.update("UPDATE board SET name='Edited demo', archived=true WHERE id=?", id);
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email IN ('alex.member@example.test',"
                + "'sam.member@example.test','morgan.moderator@example.test','avery.admin@example.test')", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email='sam.member@example.test'", String.class)).isEqualTo(changedHash);
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE email='sam.member@example.test'", String.class)).isEqualTo("MODERATOR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board WHERE slug IN ('getting-started','product-help')", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT name FROM board WHERE id=?", String.class, id)).isEqualTo("Edited demo");
        assertThat(jdbc.queryForObject("SELECT archived FROM board WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test void demoConversationsAreDeterministicAndPreserveMemberChanges() {
        UUID questionId = UUID.nameUUIDFromBytes("commonbeacon-demo-question-first-steps".getBytes(StandardCharsets.UTF_8));
        UUID replyId = UUID.nameUUIDFromBytes("commonbeacon-demo-reply-first-steps".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, questionId)).isEqualTo(replyId);
        jdbc.update("UPDATE question SET title='Edited fictional question', accepted_reply_id=null WHERE id=?", questionId);
        jdbc.update("UPDATE reply SET body='Edited fictional answer', visibility='HIDDEN' WHERE id=?", replyId);
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT title FROM question WHERE id=?", String.class, questionId)).isEqualTo("Edited fictional question");
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, questionId)).isNull();
        assertThat(jdbc.queryForObject("SELECT body FROM reply WHERE id=?", String.class, replyId)).isEqualTo("Edited fictional answer");
        assertThat(jdbc.queryForObject("SELECT visibility FROM reply WHERE id=?", String.class, replyId)).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=?", Integer.class, questionId)).isEqualTo(5);
    }
}
