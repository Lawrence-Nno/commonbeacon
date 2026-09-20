package com.lawrencenno.commonbeacon;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lawrencenno.commonbeacon.knowledge.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=article-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArticleIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminArticleService service;
    @MockitoSpyBean ArticleRepository articles;
    @MockitoSpyBean ArticleReadRepository reads;
    Browser member, moderator, admin, otherAdmin, visitor;
    class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf, user;
        String base() { return "http://127.0.0.1:" + port; }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void token() throws Exception { csrf = json.readTree(get("/api/v1/auth/csrf").body()); }
        HttpResponse<String> send(String method, String path, Map<String, ?> body, boolean withCsrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
            if (withCsrf) request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        void login(String email) throws Exception {
            token();
            var response = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                            + "&password=article-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); user = json.readTree(response.body()); token();
        }
        public void close() { client.close(); }
    }
    @BeforeAll void sessions() throws Exception {
        member = new Browser(); moderator = new Browser(); admin = new Browser(); otherAdmin = new Browser(); visitor = new Browser();
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,?,?, 'ADMINISTRATOR')",
                UUID.randomUUID(), "second.article.admin@example.test", "Second Article Admin", encoder.encode("article-test-password-42"));
        member.login("alex.member@example.test"); moderator.login("morgan.moderator@example.test");
        admin.login("avery.admin@example.test"); otherAdmin.login("second.article.admin@example.test"); visitor.token();
    }
    @AfterAll void closeSessions() { for (var actor : List.of(member, moderator, admin, otherAdmin, visitor)) actor.close(); }
    static final String ADMIN = "/api/v1/admin/articles";
    static final String PUBLIC = "/api/v1/articles";
    Map<String, Object> draft(String slug) { return Map.of("slug", slug, "title", "  A useful setup guide  ", "body", "  Fictional article body for a helpful setup guide.  "); }
    Map<String, Object> draft() { return draft("guide-" + UUID.randomUUID()); }
    UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }
    String path(JsonNode node) { return ADMIN + "/" + id(node); }
    String publicPath(JsonNode node) { return PUBLIC + "/" + node.get("slug").asText(); }
    JsonNode created() throws Exception {
        var response = admin.send("POST", ADMIN, draft(), true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var article = json.readTree(response.body());
        assertThat(response.headers().firstValue("location")).contains(path(article));
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        return article;
    }
    HttpResponse<String> action(Browser actor, JsonNode article, String name, long version) throws Exception {
        return actor.send("POST", path(article) + "/" + name, Map.of("expectedVersion", version), true);
    }
    Map<String, Object> edit(long version, String body) { return Map.of("title", "Updated article title", "body", body, "expectedVersion", version); }
    void assertMissing(Browser actor, String path) throws Exception {
        assertCode(actor.get(path), 404, "ARTICLE_NOT_FOUND");
    }
    void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains(code).doesNotContain("SELECT", "stackTrace", "org.hibernate", "ux_article_slug", "Fictional article");
    }

    @Test void completeLifecyclePreservesOriginalAuthorSlugAndPublicationTime() throws Exception {
        var article = created();
        assertThat(article.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "id", "slug", "title", "body", "status", "author", "createdAt", "updatedAt", "publishedAt", "version");
        assertThat(article.get("status").asText()).isEqualTo("DRAFT");
        assertThat(article.get("version").asLong()).isZero(); assertThat(article.get("publishedAt").isNull()).isTrue();
        assertThat(article.get("title").asText()).isEqualTo("A useful setup guide");
        assertThat(article.get("body").asText()).isEqualTo("Fictional article body for a helpful setup guide.");
        assertThat(article.get("author").get("id")).isEqualTo(admin.user.get("id"));
        assertThat(article.get("createdAt")).isEqualTo(article.get("updatedAt"));
        for (var actor : List.of(visitor, member, moderator, admin)) assertMissing(actor, publicPath(article));
        var saved = otherAdmin.send("PATCH", path(article), edit(0, "  Draft edited by a different administrator.  "), true);
        assertThat(saved.statusCode()).isEqualTo(200);
        var edited = json.readTree(saved.body()); assertThat(edited.get("version").asLong()).isEqualTo(1);
        assertThat(edited.get("author")).isEqualTo(article.get("author")); assertThat(edited.get("slug")).isEqualTo(article.get("slug"));
        var publication = action(otherAdmin, article, "publish", 1);
        assertThat(publication.statusCode()).isEqualTo(200);
        var published = json.readTree(publication.body()); assertThat(published.get("version").asLong()).isEqualTo(2);
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        var publishedAt = Instant.parse(published.get("publishedAt").asText());
        assertThat(publishedAt).isAfterOrEqualTo(Instant.parse(article.get("createdAt").asText()));
        assertThat(publishedAt.getNano() % 1000).isZero();
        String plainBody = "<script>fictional plain text</script>\nThis is stored as text, not HTML rendering.";
        var live = admin.send("PATCH", path(article), edit(2, plainBody), true);
        assertThat(live.statusCode()).isEqualTo(200);
        var updated = json.readTree(live.body()); assertThat(updated.get("publishedAt")).isEqualTo(published.get("publishedAt"));
        assertThat(updated.get("createdAt")).isEqualTo(article.get("createdAt"));
        for (var actor : List.of(visitor, member, moderator, admin)) {
            var publicResponse = actor.get(publicPath(article)); assertThat(publicResponse.statusCode()).isEqualTo(200);
            var detail = json.readTree(publicResponse.body());
            assertThat(detail.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "slug", "title", "body", "author", "publishedAt", "updatedAt");
            assertThat(detail.get("body").asText()).isEqualTo(plainBody);
            assertThat(detail.get("author").properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "displayName");
            assertThat(publicResponse.body()).doesNotContain("version", "status", "createdAt", "email", "password");
        }
        var archived = action(otherAdmin, article, "archive", 3); assertThat(archived.statusCode()).isEqualTo(200);
        var finalState = json.readTree(archived.body()); assertThat(finalState.get("version").asLong()).isEqualTo(4);
        assertThat(finalState.get("publishedAt")).isEqualTo(published.get("publishedAt"));
        assertThat(finalState.get("author")).isEqualTo(article.get("author"));
        assertThat(finalState.get("slug")).isEqualTo(article.get("slug"));
        assertMissing(visitor, publicPath(article));
        assertThat(admin.get(path(article)).statusCode()).isEqualTo(200);
    }

    @Test void onlyAdministratorsManageArticlesAtHttpAndServiceBoundaries() throws Exception {
        var article = created();
        for (var actor : List.of(visitor, member, moderator)) {
            int expected = actor == visitor ? 401 : 403;
            for (String route : List.of(ADMIN, path(article))) assertThat(actor.get(route).statusCode()).isEqualTo(expected);
            assertThat(actor.send("POST", ADMIN, draft(), true).statusCode()).isEqualTo(expected);
            assertThat(actor.send("PATCH", path(article), edit(0, "A forbidden administrator edit."), true).statusCode()).isEqualTo(expected);
            for (String name : List.of("publish", "archive")) assertThat(action(actor, article, name, 0).statusCode()).isEqualTo(expected);
        }
        for (String route : List.of(ADMIN, path(article))) assertThat(admin.get(route).headers().firstValue("cache-control")).contains("no-store");
        assertThat(admin.send("POST", ADMIN, draft(), false).statusCode()).isEqualTo(403);
        assertThat(admin.send("PATCH", path(article), edit(0, "Missing CSRF token edit."), false).statusCode()).isEqualTo(403);
        for (String name : List.of("publish", "archive")) assertThat(admin.send("POST", path(article) + "/" + name, Map.of("expectedVersion", 0), false).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> service.list(null, 0, 20)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.create(null, null)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        for (String role : List.of("ROLE_MEMBER", "ROLE_MODERATOR")) {
            var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
            security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("actor", "unused",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
            try {
                assertThatThrownBy(() -> service.list(null, 0, 20)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> service.get(id(article))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> service.create(null, null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> service.edit(id(article), null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> service.publish(id(article), null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> service.archive(id(article), null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        }
    }

    @Test void transitionsAreTerminalAndStaleChecksPrecedeStateChecks() throws Exception {
        var draft = created(); assertThat(action(admin, draft, "archive", 0).statusCode()).isEqualTo(200);
        assertThat(json.readTree(admin.get(path(draft)).body()).get("publishedAt").isNull()).isTrue();
        for (String name : List.of("publish", "archive")) {
            assertCode(action(admin, draft, name, 0), 409, "STALE_EDIT");
            assertCode(action(admin, draft, name, 1), 409, "ARTICLE_STATE_CONFLICT");
        }
        assertCode(admin.send("PATCH", path(draft), edit(0, "A stale archived draft edit."), true), 409, "STALE_EDIT");
        assertCode(admin.send("PATCH", path(draft), edit(1, "An archived draft is read only."), true), 409, "ARTICLE_STATE_CONFLICT");
        var published = created(); assertThat(action(admin, published, "publish", 0).statusCode()).isEqualTo(200);
        assertCode(action(admin, published, "publish", 1), 409, "ARTICLE_STATE_CONFLICT");
        for (var article : List.of(draft, published)) assertCode(admin.send("POST", ADMIN, draft(article.get("slug").asText()), true), 409, "ARTICLE_SLUG_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT version FROM knowledge_article WHERE id=?", Long.class, id(draft))).isEqualTo(1);
    }

    @Test void strictTrimmedValidationRejectsForgedMetadataAndMissingVersions() throws Exception {
        for (String slug : List.of("ab", "a".repeat(101), "Uppercase", "two--hyphens", "-leading", "trailing-", "has space", "has_underscore", "slash/value", "\n"))
            assertCode(admin.send("POST", ADMIN, draft(slug), true), 400, "VALIDATION_FAILED");
        var trimmed = admin.send("POST", ADMIN, draft("  trim-" + UUID.randomUUID() + "  "), true);
        assertThat(trimmed.statusCode()).isEqualTo(201);
        assertThat(json.readTree(trimmed.body()).get("slug").asText()).doesNotContain(" ");
        for (String field : List.of("slug", "title", "body")) {
            var request = new HashMap<>(draft()); request.remove(field);
            assertCode(admin.send("POST", ADMIN, request, true), 400, "VALIDATION_FAILED");
            request.put(field, null); assertCode(admin.send("POST", ADMIN, request, true), 400, "VALIDATION_FAILED");
            request.put(field, " "); assertCode(admin.send("POST", ADMIN, request, true), 400, "VALIDATION_FAILED");
        }
        for (String field : List.of("authorId", "status", "publishedAt", "createdAt", "version")) {
            var request = new HashMap<>(draft()); request.put(field, "forged");
            assertCode(admin.send("POST", ADMIN, request, true), 400, "INVALID_REQUEST");
        }
        var article = created();
        var forbiddenSlug = new HashMap<>(edit(0, "A normal article body.")); forbiddenSlug.put("slug", "different-slug");
        assertCode(admin.send("PATCH", path(article), forbiddenSlug, true), 400, "INVALID_REQUEST");
        for (String method : List.of("PATCH", "publish", "archive")) {
            String route = path(article) + (method.equals("PATCH") ? "" : "/" + method);
            var body = new HashMap<String, Object>(); if (method.equals("PATCH")) body.putAll(edit(0, "Normal edited body."));
            for (Object version : Arrays.asList(null, -1, "invalid")) {
                body.put("expectedVersion", version);
                assertThat(admin.send(method.equals("PATCH") ? "PATCH" : "POST", route, body, true).statusCode()).isEqualTo(400);
            }
            body.remove("expectedVersion"); assertCode(admin.send(method.equals("PATCH") ? "PATCH" : "POST", route, body, true), 400, "VALIDATION_FAILED");
            body.put("expectedVersion", 0); body.put("status", "PUBLISHED");
            assertCode(admin.send(method.equals("PATCH") ? "PATCH" : "POST", route, body, true), 400, "INVALID_REQUEST");
        }
        for (String field : List.of("title", "body")) {
            var request = new HashMap<>(edit(0, "Normal edited article body.")); request.remove(field);
            assertCode(admin.send("PATCH", path(article), request, true), 400, "VALIDATION_FAILED");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM knowledge_article WHERE id=?", Long.class, id(article))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_article WHERE id=?", String.class, id(article))).isEqualTo("DRAFT");
    }

    @Test void unicodeTextBoundsAgreeAtHttpAndDatabaseBoundaries() throws Exception {
        for (var lengths : List.of(new int[]{5, 10}, new int[]{200, 20000})) {
            String title = "\uD83D\uDE00".repeat(lengths[0] / 2) + (lengths[0] % 2 == 1 ? "a" : "");
            String body = "\uD83D\uDE00".repeat(lengths[1] / 2);
            var request = new HashMap<>(draft()); request.put("title", title); request.put("body", body);
            var response = admin.send("POST", ADMIN, request, true); assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json.readTree(response.body()).get("title").asText()).isEqualTo(title);
            assertThat(json.readTree(response.body()).get("body").asText()).isEqualTo(body);
        }
        for (var values : List.of(new String[]{"tiny", "Valid article body"}, new String[]{"a".repeat(201), "Valid article body"},
                new String[]{"Valid title", "too short"}, new String[]{"Valid title", "a".repeat(20001)},
                new String[]{"\uD83D\uDE00".repeat(101), "Valid article body"}, new String[]{"Valid title", "\uD83D\uDE00".repeat(10001)})) {
            var request = new HashMap<>(draft()); request.put("title", values[0]); request.put("body", values[1]);
            assertCode(admin.send("POST", ADMIN, request, true), 400, "VALIDATION_FAILED");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,author_id) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), "bounds-" + UUID.randomUUID(), values[0], values[1], id(admin.user))).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test void publicListsExcludePrivateRowsAndHaveStableOrderingCountsAndNarrowDtos() throws Exception {
        var first = created(); var second = created(); var hidden = created(); var archived = created();
        assertThat(action(admin, first, "publish", 0).statusCode()).isEqualTo(200);
        assertThat(action(admin, second, "publish", 0).statusCode()).isEqualTo(200);
        assertThat(action(admin, archived, "archive", 0).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE knowledge_article SET published_at='2030-01-01T00:00:00Z' WHERE id IN (?,?)", id(first), id(second));
        var ids = jdbc.queryForList("SELECT id FROM knowledge_article WHERE status='PUBLISHED' ORDER BY published_at DESC,id DESC", UUID.class);
        for (int page = 0; page < ids.size(); page++) {
            var response = visitor.get(PUBLIC + "?size=1&page=" + page); assertThat(response.statusCode()).isEqualTo(200);
            var data = json.readTree(response.body()); assertThat(data.get("totalElements").asInt()).isEqualTo(ids.size());
            assertThat(data.get("totalPages").asInt()).isEqualTo(ids.size());
            var item = data.get("items").get(0); assertThat(item.get("id").asText()).isEqualTo(ids.get(page).toString());
            assertThat(item.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "slug", "title", "author", "publishedAt", "updatedAt");
            assertThat(response.body()).doesNotContain(id(hidden).toString(), id(archived).toString(), "body", "version", "status", "email", "password");
        }
        assertThat(json.readTree(visitor.get(PUBLIC + "?size=1&page=" + ids.size()).body()).get("items").size()).isZero();
        assertMissing(visitor, publicPath(hidden)); assertMissing(visitor, publicPath(archived)); assertMissing(visitor, PUBLIC + "/missing-article");
    }

    @Test void administratorListsFilterAllStatusesWithStableUpdatedOrderAndValidateQueries() throws Exception {
        var a = created(); var b = created();
        jdbc.update("UPDATE knowledge_article SET updated_at='2031-01-01T00:00:00Z' WHERE id IN (?,?)", id(a), id(b));
        for (String status : Arrays.asList(null, "DRAFT", "PUBLISHED", "ARCHIVED")) {
            String filter = status == null ? "" : " WHERE status=?";
            var ids = jdbc.queryForList("SELECT id FROM knowledge_article" + filter + " ORDER BY updated_at DESC,id DESC", UUID.class,
                    status == null ? new Object[]{} : new Object[]{status});
            for (int page = 0; page < ids.size(); page++) {
                var response = admin.get(ADMIN + "?size=1&page=" + page + (status == null ? "" : "&status=" + status));
                assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("cache-control")).contains("no-store");
                var data = json.readTree(response.body()); assertThat(data.get("totalElements").asInt()).isEqualTo(ids.size());
                var item = data.get("items").get(0); assertThat(item.get("id").asText()).isEqualTo(ids.get(page).toString());
                assertThat(item.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "slug", "title", "status", "author", "createdAt", "updatedAt", "publishedAt", "version");
            }
        }
        for (String query : List.of("page=-1", "page=", "page=abc", "size=0", "size=101", "size=", "page=2147483647", "sort=title"))
            for (var route : List.of(ADMIN, PUBLIC)) assertThat(admin.get(route + "?" + query).statusCode()).as(route + query).isEqualTo(400);
        for (String status : List.of("", "ALL", "draft", "UNKNOWN")) assertCode(admin.get(ADMIN + "?status=" + status), 400, "INVALID_STATUS");
        assertCode(visitor.get(PUBLIC + "?status=DRAFT"), 400, "INVALID_REQUEST");
        assertCode(visitor.get(PUBLIC + "?includeDrafts=true"), 400, "INVALID_REQUEST");
        assertMissing(admin, ADMIN + "/" + UUID.randomUUID());
        assertThat(admin.get(ADMIN + "/not-a-uuid").statusCode()).isEqualTo(400);
        for (String action : List.of("publish", "archive")) assertCode(admin.send("POST", ADMIN + "/" + UUID.randomUUID() + "/" + action, Map.of("expectedVersion", 0), true), 404, "ARTICLE_NOT_FOUND");
        assertCode(admin.send("PATCH", ADMIN + "/" + UUID.randomUUID(), edit(0, "Missing article edit."), true), 404, "ARTICLE_NOT_FOUND");
    }

    @Test void concurrentSlugCreationCommitsOneDraftAndMapsUniqueConstraintToSafeConflict() throws Exception {
        var request = draft(); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { start.await(); return admin.send("POST", ADMIN, request, true); });
            var two = executor.submit(() -> { start.await(); return otherAdmin.send("POST", ADMIN, request, true); });
            start.countDown(); var responses = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
            assertCode(responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(), 409, "ARTICLE_SLUG_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_article WHERE slug=?", Integer.class, request.get("slug"))).isEqualTo(1);
    }

    @Test void databaseEnforcesStatusPublicationSlugsRequiredTextAuthorsAndVersion() throws Exception {
        String sql = "INSERT INTO knowledge_article(id,slug,title,body,status,author_id,published_at,version) VALUES (?,?,?,?,?,?,?,?)";
        var author = id(admin.user);
        for (Object[] values : List.of(
                new Object[]{"bad--slug", "Valid title", "Valid article body", "DRAFT", author, null, 0},
                new Object[]{"Uppercase", "Valid title", "Valid article body", "DRAFT", author, null, 0},
                new Object[]{"ab", "Valid title", "Valid article body", "DRAFT", author, null, 0},
                new Object[]{"valid-slug", "Valid title", "Valid article body", "UNKNOWN", author, null, 0},
                new Object[]{"valid-slug", "Valid title", "Valid article body", "PUBLISHED", author, null, 0},
                new Object[]{"valid-slug", "Valid title", "Valid article body", "DRAFT", author, java.sql.Timestamp.from(Instant.now()), 0},
                new Object[]{"valid-slug", "Valid title", "Valid article body", "DRAFT", UUID.randomUUID(), null, 0},
                new Object[]{"valid-slug", "Valid title", "Valid article body", "DRAFT", author, null, -1},
                new Object[]{"valid-slug", "     ", "Valid article body", "DRAFT", author, null, 0},
                new Object[]{"valid-slug", "Valid title", "          ", "DRAFT", author, null, 0},
                new Object[]{"valid-slug", "Valid title", null, "DRAFT", author, null, 0},
                new Object[]{"valid-slug", null, "Valid article body", "DRAFT", author, null, 0})) {
            var args = new ArrayList<Object>(); args.add(UUID.randomUUID()); args.addAll(Arrays.asList(values));
            assertThatThrownBy(() -> jdbc.update(sql, args.toArray())).isInstanceOf(DataIntegrityViolationException.class);
        }
        var article = created();
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), article.get("slug").asText(), "Valid title", "Valid article body", "ARCHIVED", author, null, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    List<HttpResponse<String>> race(UUID id, Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstLock = new java.util.concurrent.atomic.AtomicBoolean(true);
        var delegate = org.mockito.Mockito.mockingDetails(articles).getMockCreationSettings().getDefaultAnswer();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            if (firstLock.getAndSet(false)) {
                locked.countDown(); if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Article lock test timed out");
            }
            return result;
        }).when(articles).findForUpdate(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(first);
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).as("First writer acquired article lock").isTrue();
                var b = executor.submit(second);
                int waiting = 0; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (waiting == 0 && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%knowledge_article%'", Integer.class);
                    if (waiting == 0) Thread.sleep(20);
                }
                assertThat(waiting).as("Second writer is blocked on the real article lock").isGreaterThan(0);
                release.countDown(); return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        } finally { org.mockito.Mockito.reset(articles); }
    }

    @Test void editPublishAndArchiveRacesInBothOrdersPreserveReviewedIntent() throws Exception {
        for (String transition : List.of("publish", "archive")) for (boolean editFirst : List.of(false, true)) {
            var article = created();
            Callable<HttpResponse<String>> editing = () -> admin.send("PATCH", path(article), edit(0, "The winning administrator edit."), true);
            Callable<HttpResponse<String>> changing = () -> action(otherAdmin, article, transition, 0);
            var results = race(id(article), editFirst ? editing : changing, editFirst ? changing : editing);
            assertThat(results.get(0).statusCode()).as(results.get(0).body()).isEqualTo(200);
            assertCode(results.get(1), 409, "STALE_EDIT");
            var current = json.readTree(admin.get(path(article)).body()); assertThat(current.get("version").asLong()).isEqualTo(1);
            assertThat(current.get("status").asText()).isEqualTo(editFirst ? "DRAFT" : transition.equals("publish") ? "PUBLISHED" : "ARCHIVED");
            assertThat(current.get("body").asText()).isEqualTo(editFirst ? "The winning administrator edit." : article.get("body").asText());
            if (!editFirst && transition.equals("publish")) assertThat(visitor.get(publicPath(article)).statusCode()).isEqualTo(200);
            else assertMissing(visitor, publicPath(article));
        }
        for (boolean publishFirst : List.of(false, true)) {
            var article = created();
            var results = race(id(article), () -> action(admin, article, publishFirst ? "publish" : "archive", 0),
                    () -> action(otherAdmin, article, publishFirst ? "archive" : "publish", 0));
            assertThat(results.get(0).statusCode()).isEqualTo(200); assertCode(results.get(1), 409, "STALE_EDIT");
            var current = json.readTree(admin.get(path(article)).body()); assertThat(current.get("version").asLong()).isEqualTo(1);
            assertThat(current.get("status").asText()).isEqualTo(publishFirst ? "PUBLISHED" : "ARCHIVED");
        }
        var article = created();
        var result = race(id(article), () -> admin.send("PATCH", path(article), edit(0, "First reviewed edit wins."), true),
                () -> otherAdmin.send("PATCH", path(article), edit(0, "Second stale edit must fail."), true));
        assertThat(result.get(0).statusCode()).isEqualTo(200); assertCode(result.get(1), 409, "STALE_EDIT");
        assertThat(json.readTree(admin.get(path(article)).body()).get("body").asText()).isEqualTo("First reviewed edit wins.");
    }

    @Test void publicListUsesOneSnapshotWhenArchivalCommitsBetweenCountAndItems() throws Exception {
        var article = created(); assertThat(action(admin, article, "publish", 0).statusCode()).isEqualTo(200);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            org.mockito.Mockito.doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                var response = executor.submit(() -> action(admin, article, "archive", 1)).get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200); return result;
            }).when(reads).count("PUBLISHED");
            var page = json.readTree(visitor.get(PUBLIC + "?size=100").body());
            assertThat(page.get("items").toString()).contains(id(article).toString());
            assertThat(page.get("items").size()).isEqualTo(page.get("totalElements").asInt());
        } finally { org.mockito.Mockito.reset(reads); }
        assertMissing(visitor, publicPath(article));
        assertThat(visitor.get(PUBLIC + "?size=100").body()).doesNotContain(id(article).toString());
    }
}
