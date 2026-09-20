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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lawrencenno.commonbeacon.search.SearchRepository;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "commonbeacon.demo.enabled=true", "commonbeacon.demo.password=search-test-password-42"
})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean SearchRepository searches;
    HttpClient visitor;
    UUID author, board;
    @BeforeAll void fixtures() {
        visitor = HttpClient.newHttpClient();
        author = jdbc.queryForObject("SELECT id FROM app_user WHERE email='avery.admin@example.test'", UUID.class);
        board = UUID.randomUUID();
        jdbc.update("INSERT INTO board(id,slug,name,description,archived) VALUES (?,?,?, ?,true)", board, "search-" + board, "Search fixtures", "Fictional archived search board");
    }
    @AfterAll void close() { visitor.close(); }
    String token() { return "search" + UUID.randomUUID().toString().replace("-", ""); }
    String encoded(String q) { return URLEncoder.encode(q, StandardCharsets.UTF_8); }
    HttpResponse<String> get(HttpClient client, String query) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/search" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode search(String q) throws Exception { return page(q, 0, 20); }
    JsonNode page(String q, int page, int size) throws Exception {
        var response = get(visitor, "?q=" + encoded(q) + "&page=" + page + "&size=" + size);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    UUID article(String title, String body, String status) { return article(UUID.randomUUID(), title, body, status); }
    UUID article(UUID id, String title, String body, String status) {
        jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,status,author_id,published_at) VALUES (?,?,?,?,?,?, CASE WHEN ?='DRAFT' THEN NULL ELSE CURRENT_TIMESTAMP END)",
                id, "guide-" + id, title, body, status, author, status);
        return id;
    }
    UUID question(String title, String body, String visibility) { return question(UUID.randomUUID(), title, body, visibility); }
    UUID question(UUID id, String title, String body, String visibility) {
        jdbc.update("INSERT INTO question(id,board_id,author_id,title,body,visibility) VALUES (?,?,?,?,?,?)", id, board, author, title, body, visibility);
        return id;
    }
    List<String> identities(JsonNode page) {
        var result = new ArrayList<String>();
        page.get("items").forEach(hit -> result.add(hit.get("kind").asText() + ":" + hit.get("id").asText()));
        return result;
    }

    @Test void mergesBeforePagingWithStableKindAndPostgresUuidOrder() throws Exception {
        String q = token();
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000088"), high = UUID.fromString("80000000-0000-0000-0000-000000000088");
        for (UUID id : List.of(high, low)) {
            article(id, q + " same title", "Article plain body", "PUBLISHED");
            question(id, q + " same title", "Question plain body", "VISIBLE");
        }
        var first = page("  " + q.toUpperCase(Locale.ROOT) + "  ", 0, 3);
        assertThat(first.get("totalElements").asLong()).isEqualTo(4);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(identities(first)).containsExactly("ARTICLE:" + low, "ARTICLE:" + high, "QUESTION:" + low);
        assertThat(identities(page(q, 1, 3))).containsExactly("QUESTION:" + high);
        assertThat(page(q, 2, 3).get("items").isEmpty()).isTrue();
        assertThat(page(q, 2, 3).get("totalElements").asLong()).isEqualTo(4);
        assertThat(identities(page(q, 0, 3))).isEqualTo(identities(first));
        for (var hit : first.get("items")) {
            assertThat(hit.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("kind", "id", "title", "snippet", "url", "rank");
            assertThat(hit.get("rank").asDouble()).isZero();
            assertThat(hit.get("url").asText()).isEqualTo(hit.get("kind").asText().equals("ARTICLE") ? "/knowledge/guide-" + hit.get("id").asText() : "/questions/" + hit.get("id").asText());
        }
    }

    @Test void excludesPrivateContentRepliesAndBodyOnlyMatchesForEveryRole() throws Exception {
        String q = token(), privateText = "Private text " + token();
        UUID visible = question(q + " public question", "Public question body", "VISIBLE");
        article(q + " public article", "Public article body", "PUBLISHED");
        question(q + " hidden question", privateText, "HIDDEN");
        article(q + " private draft", privateText, "DRAFT");
        article(q + " archived article", privateText, "ARCHIVED");
        question("Unrelated question title", q + " body only private marker", "VISIBLE");
        article("Unrelated article title", q + " body only private marker", "PUBLISHED");
        jdbc.update("INSERT INTO reply(id,question_id,author_id,body) VALUES (?,?,?,?)", UUID.randomUUID(), visible, author, q + " reply only private marker");
        jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,?)", UUID.randomUUID(), author, visible, q + " report only private marker");
        var expected = search(q);
        assertThat(expected.get("totalElements").asLong()).isEqualTo(2);
        assertThat(expected.toString()).doesNotContain(privateText, "body only", "reply only", "report only", "email", "version");
        for (String email : List.of("alex.member@example.test", "morgan.moderator@example.test", "avery.admin@example.test")) {
            try (var actor = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
                var csrfResponse = actor.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
                var csrf = json.readTree(csrfResponse.body());
                var login = actor.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded").header(csrf.get("headerName").asText(), csrf.get("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString("email=" + encoded(email) + "&password=search-test-password-42")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(login.statusCode()).isEqualTo(200);
                assertThat(json.readTree(get(actor, "?q=" + encoded(q)).body())).isEqualTo(expected);
            }
        }
    }

    @Test void treatsPunctuationQuotesAndPatternCharactersLiterally() throws Exception {
        for (String literal : List.of("%", "_", "!", "\\", "'", "\"", "' OR 1=1 --", "(a|b).*", "!?%_\\")) {
            String q = token() + " " + literal;
            article(q + " guide", "Literal article body", "PUBLISHED");
            question(q + " question", "Literal question body", "VISIBLE");
            article(q.replace(literal, "expanded") + " guide", "Decoy article body", "PUBLISHED");
            assertThat(search(q).get("totalElements").asLong()).as(literal).isEqualTo(2);
        }
        assertThat(search(token()).get("totalElements").asLong()).isZero();
    }

    @Test void boundsQueryAndPageEvenForBlankInput() throws Exception {
        for (String query : List.of("", "?q=", "?q=%20%09%20", "?q=%E2%80%83")) {
            var response = get(visitor, query); assertThat(response.statusCode()).isEqualTo(200);
            var page = json.readTree(response.body()); assertThat(page.get("items").isEmpty()).isTrue();
            assertThat(page.get("totalElements").asLong()).isZero(); assertThat(page.get("totalPages").asInt()).isZero();
        }
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=2147483647&size=2", "?page=2147483648", "?page=", "?size=", "?page=one", "?size=1.5")) {
            var response = get(visitor, query); assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_PAGE");
        }
        for (String query : List.of("?q=a&q=b", "?page=0&page=1", "?status=DRAFT", "?sort=rank")) {
            assertThat(get(visitor, query).statusCode()).as(query).isEqualTo(400);
        }
        assertThat(get(visitor, "?page=2147483647&size=1").statusCode()).isEqualTo(200);
        assertThat(get(visitor, "?q=" + encoded("  " + "a".repeat(200) + "  ")).statusCode()).isEqualTo(200);
        assertThat(get(visitor, "?q=" + encoded("\uD83D\uDE00".repeat(100))).statusCode()).isEqualTo(200);
        for (String longQuery : List.of("a".repeat(201), "\uD83D\uDE00".repeat(101))) {
            var response = get(visitor, "?q=" + encoded(longQuery)); assertThat(response.statusCode()).isEqualTo(400);
            var problem = json.readTree(response.body()); assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(problem.get("fieldErrors").has("q")).isTrue(); assertThat(problem.has("requestId")).isTrue();
            assertThat(response.body()).doesNotContain("SELECT", "stackTrace", longQuery);
        }
    }

    @Test void snippetsArePlainTextBoundedWithoutSplittingSurrogates() throws Exception {
        var bodies = List.of("<script>literal text</script> & <b>not markup</b>", "a".repeat(240), "b".repeat(241),
                "c".repeat(238) + "\uD83D\uDE00" + "tail", "\uD83D\uDE00".repeat(130));
        for (String body : bodies) {
            String q = token(); article(q + " <img>literal title", body, "PUBLISHED");
            String snippet = search(q).get("items").get(0).get("snippet").asText();
            assertThat(snippet.length()).isLessThanOrEqualTo(240);
            if (body.length() <= 240) assertThat(snippet).isEqualTo(body);
            else {
                assertThat(snippet).endsWith("\u2026");
                assertThat(body).startsWith(snippet.substring(0, snippet.length() - 1));
                assertThat(Character.isHighSurrogate(snippet.charAt(snippet.length() - 2))).isFalse();
            }
            for (int i = 0; i < snippet.length(); i++) {
                if (Character.isHighSurrogate(snippet.charAt(i))) assertThat(Character.isLowSurrogate(snippet.charAt(++i))).isTrue();
                else assertThat(Character.isLowSurrogate(snippet.charAt(i))).isFalse();
            }
        }
    }

    @Test void committedEditsAndVisibilityChangesAffectNextSearch() throws Exception {
        String q = token(), next = token();
        UUID question = question(q + " question", "Original question body", "VISIBLE");
        UUID article = article(q + " article", "Original article body", "DRAFT");
        assertThat(search(q).get("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE knowledge_article SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE id=?", article);
        assertThat(search(q).get("totalElements").asLong()).isEqualTo(2);
        jdbc.update("UPDATE question SET visibility='HIDDEN' WHERE id=?", question);
        assertThat(search(q).get("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE question SET visibility='VISIBLE',title=?,body=? WHERE id=?", next + " renamed", "Updated snippet for next search", question);
        assertThat(search(next).get("items").get(0).get("snippet").asText()).isEqualTo("Updated snippet for next search");
        jdbc.update("UPDATE knowledge_article SET title=?,body=? WHERE id=?", next + " renamed article", "Updated article snippet", article);
        assertThat(search(q).get("totalElements").asLong()).isZero();
        assertThat(search(next).get("totalElements").asLong()).isEqualTo(2);
        jdbc.update("UPDATE knowledge_article SET status='ARCHIVED' WHERE id=?", article);
        assertThat(search(next).get("totalElements").asLong()).isEqualTo(1);
    }

    @Test void countAndHitsShareASnapshotDuringConcurrentArchival() throws Exception {
        String q = token(); UUID id = article(q + " snapshot", "Snapshot article body", "PUBLISHED");
        var counted = new CountDownLatch(1); var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object total = invocation.callRealMethod(); counted.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Search snapshot test timed out");
            return total;
        }).when(searches).count(q);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(() -> search(q));
            try {
                assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE knowledge_article SET status='ARCHIVED' WHERE id=?", id);
                release.countDown();
                var page = response.get(15, TimeUnit.SECONDS);
                assertThat(page.get("totalElements").asLong()).isEqualTo(1);
                assertThat(identities(page)).containsExactly("ARTICLE:" + id);
            } finally { release.countDown(); }
        } finally { org.mockito.Mockito.reset(searches); }
        assertThat(search(q).get("totalElements").asLong()).isZero();
    }
}
