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
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=moderation-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.lawrencenno.commonbeacon.moderation.ModerationReadService readService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.lawrencenno.commonbeacon.moderation.ModerationReadRepository readRepository;
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
                            + "&password=moderation-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
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

    @Test void onlyModeratorsAndAdministratorsCanReadReportsAtHttpAndServiceBoundaries() throws Exception {
        var question = member.create(board()); var reportId = reportId(question);
        for (var path : List.of("/api/v1/moderation/reports", reviewPath(reportId))) {
            assertThat(visitor.get(path).statusCode()).isEqualTo(401);
            for (var actor : List.of(member, other)) assertThat(actor.get(path).statusCode()).isEqualTo(403);
            for (var actor : List.of(moderator, admin)) {
                var response = actor.get(path);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("cache-control")).contains("no-store");
                assertThat(response.body()).doesNotContain("email", "password", "@example.test");
            }
        }
        assertThatThrownBy(() -> readService.list("OPEN", 0, 20)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> readService.get(reportId)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "member", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))));
        try {
            assertThatThrownBy(() -> readService.list("OPEN", 0, 20)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> readService.get(reportId)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void hiddenReplyAndParentContextIsPrivilegedWhilePublicReadsStayHidden() throws Exception {
        var board = board(); var question = member.create(board); var reply = reply(question, other);
        var replyReport = id(json.readTree(report(member, "replyId", id(reply).toString()).body()));
        var questionReport = reportId(question);
        jdbc.update("UPDATE question SET visibility='HIDDEN', accepted_reply_id=?, version=3 WHERE id=?", id(reply), id(question));
        jdbc.update("UPDATE board SET archived=true WHERE id=?", board);
        var detail = json.readTree(moderator.get(reviewPath(replyReport)).body());
        var context = detail.get("context");
        assertThat(context.get("board").get("archived").asBoolean()).isTrue();
        assertThat(context.get("question").get("visibility").asText()).isEqualTo("HIDDEN");
        assertThat(context.get("question").get("version").asLong()).isEqualTo(3);
        assertThat(context.get("question").get("acceptedReplyId").asText()).isEqualTo(id(reply).toString());
        assertThat(context.get("reply").get("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(context.get("reply").get("body")).isEqualTo(reply.get("body"));
        assertThat(context.get("effectivePublicVisibility").asBoolean()).isFalse();
        assertThat(detail.get("availableDecisions").toString()).isEqualTo("[\"DISMISS\",\"HIDE\"]");
        jdbc.update("UPDATE reply SET visibility='HIDDEN', version=2 WHERE id=?", id(reply));
        var hidden = json.readTree(admin.get(reviewPath(replyReport)).body());
        assertThat(hidden.get("availableDecisions").toString()).isEqualTo("[\"DISMISS\",\"ACKNOWLEDGE_HIDDEN\"]");
        assertThat(hidden.get("context").get("reply").get("version").asLong()).isEqualTo(2);
        var parent = json.readTree(moderator.get(reviewPath(questionReport)).body());
        assertThat(parent.get("context").get("reply").isNull()).isTrue();
        assertThat(parent.get("availableDecisions").toString()).contains("ACKNOWLEDGE_HIDDEN");
        for (var actor : List.of(visitor, member, moderator, admin)) {
            assertThat(actor.get(path(question)).statusCode()).isEqualTo(404);
            assertThat(actor.get(replyPath(reply)).statusCode()).isEqualTo(404);
            assertThat(actor.get(repliesPath(question)).statusCode()).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM content_report WHERE id=?", String.class, replyReport)).isEqualTo("OPEN");
    }

    @Test void queueFiltersCountsStableTiesAndOutOfRangePagesMatchStorage() throws Exception {
        var first = reportId(member.create(board())); var second = reportId(member.create(board()));
        jdbc.update("UPDATE content_report SET created_at='2020-01-01T00:00:00Z' WHERE id IN (?,?)", first, second);
        var resolved = reportId(member.create(board()));
        jdbc.update("UPDATE content_report SET status='RESOLVED', resolver_id=?, resolved_at=now(), resolution_decision='DISMISS', resolution_note='Reviewed with no violation', version=1 WHERE id=?",
                id(admin.user), resolved);
        for (var status : List.of("OPEN", "RESOLVED")) {
            var ids = jdbc.queryForList("SELECT id FROM content_report WHERE status=? ORDER BY created_at,id", UUID.class, status);
            for (int page = 0; page < ids.size(); page++) {
                var response = moderator.get("/api/v1/moderation/reports?status=" + status + "&size=1&page=" + page);
                assertThat(response.statusCode()).isEqualTo(200);
                var body = json.readTree(response.body());
                assertThat(body.get("totalElements").asInt()).isEqualTo(ids.size());
                assertThat(body.get("totalPages").asInt()).isEqualTo(ids.size());
                assertThat(body.get("items").get(0).get("id").asText()).isEqualTo(ids.get(page).toString());
            }
            var beyond = json.readTree(moderator.get("/api/v1/moderation/reports?status=" + status + "&size=1&page=" + ids.size()).body());
            assertThat(beyond.get("items").size()).isZero();
            assertThat(beyond.get("totalElements").asInt()).isEqualTo(ids.size());
        }
        var detail = json.readTree(admin.get(reviewPath(resolved)).body());
        assertThat(detail.get("report").get("resolver").get("id")).isEqualTo(admin.user.get("id"));
        assertThat(detail.get("report").get("resolutionNote").asText()).isEqualTo("Reviewed with no violation");
        assertThat(detail.get("report").get("version").asInt()).isEqualTo(1);
        assertThat(detail.get("availableDecisions").size()).isZero();
    }

    @Test void invalidFiltersBoundsAndMissingReportsAreControlled() throws Exception {
        for (var query : List.of("status=ALL", "status=", "page=-1", "size=0", "size=101", "page=2147483647", "page=abc", "sort=reason", "includeHidden=true")) {
            assertThat(moderator.get("/api/v1/moderation/reports?" + query).statusCode()).as(query).isEqualTo(400);
        }
        var missing = moderator.get(reviewPath(UUID.randomUUID()));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("REPORT_NOT_FOUND").doesNotContain("SELECT", "stackTrace");
        assertThat(moderator.get("/api/v1/moderation/reports/not-a-uuid").statusCode()).isEqualTo(400);
    }

    @Test void detailKeepsOneSnapshotWhenContentChangesBetweenReportAndContextReads() throws Exception {
        var question = member.create(board()); var reportId = reportId(question);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                executor.submit(() -> jdbc.update("UPDATE question SET visibility='HIDDEN', version=version+1 WHERE id=?", id(question)))
                        .get(10, TimeUnit.SECONDS);
                return result;
            }).when(readRepository).find(reportId);
            var response = moderator.get(reviewPath(reportId));
            assertThat(response.statusCode()).isEqualTo(200);
            var context = json.readTree(response.body()).get("context");
            assertThat(context.get("question").get("visibility").asText()).isEqualTo("VISIBLE");
            assertThat(context.get("question").get("version").asLong()).isZero();
            assertThat(context.get("effectivePublicVisibility").asBoolean()).isTrue();
            assertThat(visitor.get(path(question)).statusCode()).isEqualTo(404);
        } finally { org.mockito.Mockito.reset(readRepository); }
        assertThat(json.readTree(moderator.get(reviewPath(reportId)).body()).get("context").get("effectivePublicVisibility").asBoolean()).isFalse();
    }
}
