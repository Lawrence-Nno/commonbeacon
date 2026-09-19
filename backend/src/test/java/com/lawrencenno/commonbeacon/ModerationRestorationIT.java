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
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=restoration-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationRestorationIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.lawrencenno.commonbeacon.moderation.ModerationResolutionService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.lawrencenno.commonbeacon.moderation.ModerationActionRepository actions;
    @Autowired com.lawrencenno.commonbeacon.moderation.ModerationContentService content;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.lawrencenno.commonbeacon.board.BoardRepository boards;
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
                            + "&password=restoration-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
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

    String contentPath(UUID id, boolean reply) { return "/api/v1/moderation/" + (reply ? "replies/" : "questions/") + id; }
    Map<String, Object> restoration(UUID id, boolean reply) throws Exception {
        var c = json.readTree(moderator.get(contentPath(id, reply)).body());
        var body = new HashMap<String, Object>(); body.put("reason", "  Safe to restore after review.  ");
        body.put("expectedTargetVersion", c.get(reply ? "reply" : "question").get("version").asLong());
        if (reply) body.put("expectedQuestionVersion", c.get("question").get("version").asLong());
        return body;
    }
    HttpResponse<String> restore(Browser actor, UUID id, boolean reply, Map<String, ?> body) throws Exception {
        return actor.send("POST", contentPath(id, reply) + "/restore", body, true);
    }
    void hide(UUID report) throws Exception {
        var response = resolve(moderator, report, decision(report, "HIDE"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test void restoreAcceptedReplyNeverReacceptsOrReopensReportsAndWorksOnArchivedBoard() throws Exception {
        var board = board(); var q = member.create(board); var r = reply(q, other); accept(q, r);
        var report = replyReport(r, member); hide(report);
        var resolvedBefore = json.readTree(moderator.get(reviewPath(report)).body()).get("report");
        jdbc.update("UPDATE board SET archived=true WHERE id=?", board);
        var response = restore(admin, id(r), true, restoration(id(r), true));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var c = json.readTree(response.body());
        assertThat(c.get("reply").get("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(c.get("reply").get("version").asInt()).isEqualTo(2);
        assertThat(c.get("question").get("version").asInt()).isEqualTo(2);
        assertThat(c.get("question").get("acceptedReplyId").isNull()).isTrue();
        assertThat(c.get("effectivePublicVisibility").asBoolean()).isTrue();
        assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(visitor.get(path(q)).body()).get("solved").asBoolean()).isFalse();
        assertThat(json.readTree(moderator.get(reviewPath(report)).body()).get("report")).isEqualTo(resolvedBefore);
        assertThat(actionCount(id(r))).isEqualTo(2);
        assertThat(member.send("PUT", path(q) + "/accepted-reply", Map.of("replyId", id(r), "expectedVersion", 2), true).statusCode()).isEqualTo(409);
        var repeated = restore(moderator, id(r), true, restoration(id(r), true));
        assertThat(repeated.statusCode()).isEqualTo(409); assertThat(repeated.body()).contains("MODERATION_STATE_CONFLICT");
        assertThat(actionCount(id(r))).isEqualTo(2);
        jdbc.update("UPDATE board SET archived=false WHERE id=?", board);
        assertThat(admin.send("PUT", path(q) + "/accepted-reply", Map.of("replyId", id(r), "expectedVersion", 2), true).statusCode()).isEqualTo(403);
        assertThat(member.send("PUT", path(q) + "/accepted-reply", Map.of("replyId", id(r), "expectedVersion", 2), true).statusCode()).isEqualTo(200);
    }

    @Test void restoreParentPreservesMixedChildStatesAndRetainedSelection() throws Exception {
        var q = member.create(board()); var selected = reply(q, other); var hidden = reply(q, member);
        accept(q, selected); var parentReport = reportId(q); var childReport = replyReport(hidden, other);
        hide(childReport); hide(parentReport);
        assertThat(restore(moderator, id(q), false, restoration(id(q), false)).statusCode()).isEqualTo(200);
        var publicQ = json.readTree(visitor.get(path(q)).body());
        assertThat(publicQ.get("solved").asBoolean()).isTrue();
        assertThat(publicQ.get("acceptedReply").get("id")).isEqualTo(selected.get("id"));
        assertThat(visitor.get(replyPath(hidden)).statusCode()).isEqualTo(404);
        assertThat(visitor.get(replyPath(selected)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT version FROM reply WHERE id=?", Long.class, id(selected))).isZero();
        assertThat(actionCount(id(q))).isEqualTo(2);
    }

    @Test void restoreReplyBeneathHiddenParentRemainsPrivateUntilParentRestoration() throws Exception {
        var q = member.create(board()); var r = reply(q, other); accept(q, r);
        var qr = reportId(q); var rr = replyReport(r, member); hide(qr); hide(rr);
        var response = restore(moderator, id(r), true, restoration(id(r), true));
        assertThat(response.statusCode()).isEqualTo(200);
        var c = json.readTree(response.body());
        assertThat(c.get("reply").get("visibility").asText()).isEqualTo("VISIBLE");
        assertThat(c.get("effectivePublicVisibility").asBoolean()).isFalse();
        assertThat(c.get("question").get("acceptedReplyId").isNull()).isTrue();
        assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(404);
        assertThat(restore(admin, id(q), false, restoration(id(q), false)).statusCode()).isEqualTo(200);
        assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(visitor.get(path(q)).body()).get("solved").asBoolean()).isFalse();
    }

    @Test void contextHistoryAndRestorationAreRoleProtectedValidatedAndPubliclyPrivate() throws Exception {
        var q = member.create(board()); var r = reply(q, other); var report = replyReport(r, member); hide(report);
        var body = restoration(id(r), true);
        for (boolean reply : List.of(false, true)) {
            var target = reply ? id(r) : id(q);
            for (String route : List.of(contentPath(target, reply), contentPath(target, reply) + "/actions")) {
                assertThat(visitor.get(route).statusCode()).isEqualTo(401);
                assertThat(member.get(route).statusCode()).isEqualTo(403);
                for (var actor : List.of(moderator, admin)) {
                    var response = actor.get(route); assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
                    assertThat(response.body()).doesNotContain("email", "password", "@example.test");
                }
            }
            assertThat(moderator.get(contentPath(UUID.randomUUID(), reply)).statusCode()).isEqualTo(404);
            assertThat(moderator.get(contentPath(UUID.randomUUID(), reply) + "/actions").statusCode()).isEqualTo(404);
            assertThat(restore(moderator, UUID.randomUUID(), reply, reply ? body : Map.of("reason", "Valid reason", "expectedTargetVersion", 0)).statusCode()).isEqualTo(404);
        }
        assertThat(restore(visitor, id(r), true, body).statusCode()).isEqualTo(401);
        assertThat(restore(member, id(r), true, body).statusCode()).isEqualTo(403);
        assertThat(moderator.send("POST", contentPath(id(r), true) + "/restore", body, false).statusCode()).isEqualTo(403);
        for (String field : List.of("expectedTargetVersion", "expectedQuestionVersion")) {
            var invalid = new HashMap<>(body); invalid.remove(field);
            assertThat(restore(moderator, id(r), true, invalid).statusCode()).isEqualTo(400);
            invalid.put(field, -1); assertThat(restore(moderator, id(r), true, invalid).statusCode()).isEqualTo(400);
            invalid.put(field, 100); var stale = restore(moderator, id(r), true, invalid);
            assertThat(stale.statusCode()).isEqualTo(409); assertThat(stale.body()).contains("STALE_EDIT");
        }
        for (String reason : List.of("    ", "tiny", "x".repeat(2001), "\uD83D\uDE00".repeat(1001))) {
            var invalid = new HashMap<>(body); invalid.put("reason", reason);
            assertThat(restore(moderator, id(r), true, invalid).statusCode()).isEqualTo(400);
        }
        var invalid = new HashMap<>(body); invalid.put("actorId", id(member.user));
        assertThat(restore(moderator, id(r), true, invalid).statusCode()).isEqualTo(400);
        assertThat(restore(moderator, id(q), false, body).statusCode()).isEqualTo(400);
        assertThat(actionCount(id(r))).isEqualTo(1);
        assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> content.get(id(r), true)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> content.history(id(r), true, 0, 20)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> content.restore(id(r), true, null, null)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("member", "unused",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))));
        try {
            assertThatThrownBy(() -> content.get(id(r), true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> content.history(id(r), true, 0, 20)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> content.restore(id(r), true, null, null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void historyHasBoundedStableNewestFirstPagesAndNoInventedResolutionEvents() throws Exception {
        var q = member.create(board()); var report = reportId(q); hide(report);
        assertThat(restore(admin, id(q), false, restoration(id(q), false)).statusCode()).isEqualTo(200);
        var history = json.readTree(moderator.get(contentPath(id(q), false) + "/actions").body());
        assertThat(history.get("items").get(0).get("action").asText()).isEqualTo("RESTORE");
        assertThat(history.get("items").get(0).get("actor").get("id")).isEqualTo(admin.user.get("id"));
        assertThat(history.get("items").get(0).get("reason").asText()).isEqualTo("Safe to restore after review.");
        assertThat(history.get("items").get(1).get("action").asText()).isEqualTo("HIDE");
        jdbc.update("UPDATE moderation_action SET created_at='2020-01-01T00:00:00Z' WHERE question_id=?", id(q));
        var ordered = jdbc.queryForList("SELECT id FROM moderation_action WHERE question_id=? ORDER BY created_at DESC,id DESC", UUID.class, id(q));
        for (int page = 0; page < ordered.size(); page++) {
            var result = json.readTree(moderator.get(contentPath(id(q), false) + "/actions?size=1&page=" + page).body());
            assertThat(result.get("items").get(0).get("id").asText()).isEqualTo(ordered.get(page).toString());
            assertThat(result.get("totalElements").asInt()).isEqualTo(2);
            assertThat(result.get("totalPages").asInt()).isEqualTo(2);
        }
        assertThat(json.readTree(moderator.get(contentPath(id(q), false) + "/actions?page=99").body()).get("items").size()).isZero();
        for (String query : List.of("page=-1", "page=", "page=bad", "size=0", "size=101", "page=2147483647", "size=", "sort=reason", "includeHidden=true"))
            assertThat(moderator.get(contentPath(id(q), false) + "/actions?" + query).statusCode()).as(query).isEqualTo(400);
        var dismissed = reportId(q);
        assertThat(resolve(admin, dismissed, decision(dismissed, "DISMISS")).statusCode()).isEqualTo(200);
        assertThat(actionCount(id(q))).isEqualTo(2);
    }

    @Test void restoreFailureAfterAuditInsertRollsBackVisibilityVersionsAndAction() throws Exception {
        var q = member.create(board()); var r = reply(q, other); var report = replyReport(r, member); hide(report);
        var before = moderator.get(contentPath(id(r), true)).body();
        org.mockito.Mockito.doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Injected restore failure"); })
                .when(actions).appendRestore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(id(r)), org.mockito.ArgumentMatchers.anyString());
        try { assertThat(restore(admin, id(r), true, restoration(id(r), true)).statusCode()).isEqualTo(500); }
        finally { org.mockito.Mockito.reset(actions); }
        assertThat(moderator.get(contentPath(id(r), true)).body()).isEqualTo(before);
        assertThat(actionCount(id(r))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM content_report WHERE id=?", String.class, report)).isEqualTo("RESOLVED");
    }

    // Pause the first real board lock, observe the second SQL transaction waiting, then release.
    List<HttpResponse<String>> race(UUID board, Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstLock = new java.util.concurrent.atomic.AtomicBoolean(true);
        var delegate = org.mockito.Mockito.mockingDetails(boards).getMockCreationSettings().getDefaultAnswer();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            if (firstLock.getAndSet(false)) {
                locked.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out");
            }
            return result;
        }).when(boards).findForUpdate(board);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(first);
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).as("First request acquired board lock").isTrue();
                var b = executor.submit(second);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                while (waiting == 0 && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%board%'", Integer.class);
                    if (waiting == 0) Thread.sleep(20);
                }
                assertThat(waiting).as("Second request is waiting on PostgreSQL board lock").isGreaterThan(0);
                release.countDown();
                return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        } finally { org.mockito.Mockito.reset(boards); }
    }

    @Test void acceptVersusHideReplyAndParentInBothOrdersPreservesSelectionInvariant() throws Exception {
        for (boolean parent : List.of(false, true)) for (boolean acceptFirst : List.of(false, true)) {
            var board = board(); var q = member.create(board); var r = reply(q, other);
            var report = parent ? reportId(q) : replyReport(r, member); var hideBody = decision(report, "HIDE");
            Callable<HttpResponse<String>> accepting = () -> member.send("PUT", path(q) + "/accepted-reply", Map.of("replyId", id(r), "expectedVersion", 0), true);
            Callable<HttpResponse<String>> hiding = () -> resolve(moderator, report, hideBody);
            var results = race(board, acceptFirst ? accepting : hiding, acceptFirst ? hiding : accepting);
            assertThat(results.get(0).statusCode()).isEqualTo(200);
            assertThat(results.get(1).statusCode()).as(results.get(1).body()).isEqualTo(acceptFirst ? 409 : 404);
            if (acceptFirst) { assertThat(results.get(1).body()).contains("STALE_EDIT"); hide(report); }
            var selected = jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, id(q));
            if (parent && acceptFirst) assertThat(selected).isEqualTo(id(r)); else assertThat(selected).isNull();
            assertThat(actionCount(parent ? id(q) : id(r))).isEqualTo(1);
            if (parent) assertThat(visitor.get(path(q)).statusCode()).isEqualTo(404);
            else assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(404);
        }
    }

    @Test void hideVersusAuthorEditsAndReplyCreationInBothOrdersRechecksLockedState() throws Exception {
        for (String write : List.of("questionEdit", "replyEdit", "createReply")) for (boolean writeFirst : List.of(false, true)) {
            var board = board(); var q = member.create(board); var r = reply(q, other);
            boolean replyTarget = write.equals("replyEdit");
            var report = replyTarget ? replyReport(r, member) : reportId(q); var hideBody = decision(report, "HIDE");
            Callable<HttpResponse<String>> authorWrite = () -> switch (write) {
                case "questionEdit" -> member.send("PATCH", path(q), Map.of("title", "Reviewed author edit", "body", "Author edit before moderation.", "expectedVersion", 0), true);
                case "replyEdit" -> other.send("PATCH", replyPath(r), Map.of("body", "Reply edit before moderation.", "expectedVersion", 0), true);
                default -> other.send("POST", repliesPath(q), Map.of("body", "New reply during moderation."), true);
            };
            Callable<HttpResponse<String>> hiding = () -> resolve(moderator, report, hideBody);
            var result = race(board, writeFirst ? authorWrite : hiding, writeFirst ? hiding : authorWrite);
            assertThat(result.get(0).statusCode()).as(result.get(0).body()).isEqualTo(writeFirst && write.equals("createReply") ? 201 : 200);
            int expected = !writeFirst ? 404 : write.equals("createReply") ? 200 : 409;
            assertThat(result.get(1).statusCode()).as(result.get(1).body()).isEqualTo(expected);
            if (expected == 409) hide(report);
            assertThat(visitor.get(replyTarget ? replyPath(r) : path(q)).statusCode()).isEqualTo(404);
            assertThat(actionCount(replyTarget ? id(r) : id(q))).isEqualTo(1);
            if (write.equals("createReply")) assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=?", Integer.class, id(q))).isEqualTo(writeFirst ? 2 : 1);
        }
    }

    @Test void twoReportsHidingSameReplyRequireExplicitAcknowledgementAndOneAction() throws Exception {
        var board = board(); var q = member.create(board); var r = reply(q, other);
        var first = replyReport(r, member); var second = replyReport(r, other);
        var one = decision(first, "HIDE"); var two = decision(second, "HIDE");
        var results = race(board, () -> resolve(moderator, first, one), () -> resolve(admin, second, two));
        assertThat(results.get(0).statusCode()).isEqualTo(200);
        assertThat(results.get(1).statusCode()).isEqualTo(409); assertOpen(second);
        var currentHide = resolve(admin, second, decision(second, "HIDE"));
        assertThat(currentHide.statusCode()).isEqualTo(409); assertThat(currentHide.body()).contains("MODERATION_STATE_CONFLICT");
        assertThat(resolve(admin, second, decision(second, "ACKNOWLEDGE_HIDDEN")).statusCode()).isEqualTo(200);
        assertThat(actionCount(id(r))).isEqualTo(1);
    }

    @Test void restoreVersusAnotherHideInBothOrdersRequiresReviewAndHasExactlyOneActionPerTransition() throws Exception {
        for (boolean restoreFirst : List.of(false, true)) {
            var board = board(); var q = member.create(board); var r = reply(q, other);
            var first = replyReport(r, member); var remaining = replyReport(r, other); hide(first);
            var restoreBody = restoration(id(r), true); var hideBody = decision(remaining, "HIDE");
            Callable<HttpResponse<String>> restoring = () -> restore(moderator, id(r), true, restoreBody);
            Callable<HttpResponse<String>> hiding = () -> resolve(admin, remaining, hideBody);
            var result = race(board, restoreFirst ? restoring : hiding, restoreFirst ? hiding : restoring);
            assertThat(result.get(restoreFirst ? 0 : 1).statusCode()).isEqualTo(200);
            assertThat(result.get(restoreFirst ? 1 : 0).statusCode()).isEqualTo(409);
            assertOpen(remaining); assertThat(actionCount(id(r))).isEqualTo(2);
            assertThat(visitor.get(replyPath(r)).statusCode()).isEqualTo(200);
            hide(remaining); assertThat(actionCount(id(r))).isEqualTo(3);
        }
    }

    @Test void hideAndRestoreVersusArchivalInBothOrdersRemainAvailableWithoutAuthorBypass() throws Exception {
        for (boolean restoring : List.of(false, true)) for (boolean archiveFirst : List.of(false, true)) {
            var board = board(); var q = member.create(board); var report = reportId(q);
            if (restoring) hide(report);
            var body = restoring ? restoration(id(q), false) : decision(report, "HIDE");
            Callable<HttpResponse<String>> archive = () -> admin.send("PATCH", "/api/v1/boards/" + board, Map.of("archived", true, "expectedVersion", 0), true);
            Callable<HttpResponse<String>> moderate = () -> restoring ? restore(moderator, id(q), false, body) : resolve(moderator, report, body);
            var result = race(board, archiveFirst ? archive : moderate, archiveFirst ? moderate : archive);
            assertThat(result).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
            assertThat(jdbc.queryForObject("SELECT archived FROM board WHERE id=?", Boolean.class, board)).isTrue();
            assertThat(actionCount(id(q))).isEqualTo(restoring ? 2 : 1);
            if (restoring) assertThat(member.send("PATCH", path(q), Map.of("title", "Author must not edit", "body", "Archived board rejects this edit.", "expectedVersion", 2), true).statusCode()).isEqualTo(409);
        }
    }
}
