package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.IdentityService;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, IdentityIT.ProbeController.class})
@org.springframework.test.context.ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.MethodName.class)
class IdentityIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    static final String PASSWORD = "a-long-test-password-42";

    @RestController
    static class ProbeController {
        private final IdentityService identity;
        ProbeController(IdentityService identity) { this.identity = identity; }
        @GetMapping("/api/v1/admin/probe") String admin() { return "allowed"; }
        @GetMapping("/api/v1/moderation/probe") String moderator() { return "allowed"; }
        @GetMapping("/api/v1/testing/owned/{id}")
        String owned(@PathVariable UUID id, Authentication authentication) {
            identity.requireOwner(authentication, id);
            return "allowed";
        }
    }

    class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        JsonNode csrf;
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        void refreshCsrf() throws Exception {
            var result = get("/api/v1/auth/csrf");
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().firstValue("cache-control").orElse("")).contains("no-store");
            csrf = json.readTree(result.body());
        }
        HttpResponse<String> post(String path, String body, boolean withCsrf, boolean form) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base() + path))
                    .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
            if (withCsrf) request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> register(String email) throws Exception {
            refreshCsrf();
            return post("/api/v1/auth/register", json.writeValueAsString(Map.of(
                    "email", email, "displayName", " Test Member ", "password", PASSWORD,
                    "role", "ADMINISTRATOR")), true, false);
        }
        HttpResponse<String> login(String email, String password) throws Exception {
            refreshCsrf();
            return post("/api/v1/auth/login", "email=" + URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8), true, true);
        }
        String session() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID"))
                    .findFirst().orElseThrow().getValue();
        }
        public void close() { client.close(); }
    }
    String base() { return "http://127.0.0.1:" + port; }
    String email() { return UUID.randomUUID() + "@example.test"; }

    @Test void aRegistrationNormalizesAndCannotGrantRoles() throws Exception {
        String email = email();
        try (var browser = new Browser()) {
            var response = browser.register(" " + email.toUpperCase(Locale.ROOT) + " ");
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).contains("MEMBER", "Test Member").doesNotContain("password", "email");
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
            String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email=?", String.class, email);
            assertThat(hash).startsWith("{pbkdf2}").isNotEqualTo(PASSWORD);
            assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        }
    }

    @Test void bSessionRotationAndLogoutInvalidateOldCookieAndCsrf() throws Exception {
        try (var browser = new Browser()) {
            String email = email();
            browser.register(email);
            String beforeLogin = browser.session();
            assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
            assertThat(browser.session()).isNotEqualTo(beforeLogin);
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
            // Login invalidates the previous CSRF token.
            assertThat(browser.post("/api/v1/auth/logout", "", true, false).statusCode()).isEqualTo(403);
            browser.refreshCsrf();
            String authenticatedSession = browser.session();
            assertThat(browser.post("/api/v1/auth/logout", "", true, false).statusCode()).isEqualTo(204);
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
            try (var replay = HttpClient.newHttpClient()) {
                var response = replay.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/me"))
                        .header("Cookie", "JSESSIONID=" + authenticatedSession).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
            }
        }
    }

    @Test void cMissingCsrfRejectsRegistrationLoginAndAuthenticatedLogout() throws Exception {
        try (var browser = new Browser()) {
            assertThat(browser.post("/api/v1/auth/register", "{}", false, false).statusCode()).isEqualTo(403);
            assertThat(browser.post("/api/v1/auth/login", "", false, true).statusCode()).isEqualTo(403);
            String email = email();
            browser.register(email);
            assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
            var denied = browser.post("/api/v1/auth/logout", "", false, false);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("CSRF_INVALID").doesNotContain("stackTrace");
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
        }
    }

    @Test void dBadCredentialsAreGenericForExistingAndAbsentAccounts() throws Exception {
        try (var browser = new Browser()) {
            String email = email();
            browser.register(email);
            var wrong = browser.login(email, "wrong-password");
            var missing = browser.login(email(), "wrong-password");
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(json.readTree(wrong.body()).get("detail")).isEqualTo(json.readTree(missing.body()).get("detail"));
        }
    }

    @Test void eValidationReturnsFieldsWithoutEchoingPasswords() throws Exception {
        try (var browser = new Browser()) {
            browser.refreshCsrf();
            var invalid = browser.post("/api/v1/auth/register",
                    "{\"email\":\"invalid\",\"displayName\":\" \",\"password\":\"short\"}", true, false);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("fieldErrors", "VALIDATION_FAILED").doesNotContain("short");
        }
    }

    @Test void fConcurrentRegistrationAllowsOneNormalizedEmail() throws Exception {
        String email = email();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Integer> task = () -> {
                try (var browser = new Browser()) {
                    start.await();
                    return browser.register(email).statusCode();
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email=?", Integer.class, email)).isEqualTo(1);
        }
    }

    @Test void gRoleAndOwnershipChecksAreEnforcedForRealSessions() throws Exception {
        for (var role : List.of("MEMBER", "MODERATOR", "ADMINISTRATOR")) {
            try (var browser = new Browser()) {
                String email = email();
                var registered = json.readTree(browser.register(email).body());
                jdbc.update("UPDATE app_user SET role=? WHERE email=?", role, email);
                assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
                assertThat(browser.get("/api/v1/admin/probe").statusCode()).isEqualTo(role.equals("ADMINISTRATOR") ? 200 : 403);
                assertThat(browser.get("/api/v1/moderation/probe").statusCode()).isEqualTo(role.equals("MEMBER") ? 403 : 200);
                assertThat(browser.get("/api/v1/testing/owned/" + registered.get("id").asText()).statusCode()).isEqualTo(200);
                assertThat(browser.get("/api/v1/testing/owned/" + UUID.randomUUID()).statusCode()).isEqualTo(403);
            }
        }
    }

    @Test void hSessionCookieHasRequiredLocalAttributes() throws Exception {
        try (var browser = new Browser()) {
            var response = browser.get("/api/v1/auth/csrf");
            String cookie = response.headers().firstValue("set-cookie").orElseThrow();
            assertThat(cookie).contains("HttpOnly", "SameSite=Lax");
        }
    }

    @Test void zLoginRateLimitReturns429OverHttp() throws Exception {
        try (var browser = new Browser()) {
            int status = 0;
            for (int i = 0; i < 11 && status != 429; i++) status = browser.login(email(), PASSWORD).statusCode();
            assertThat(status).isEqualTo(429);
        }
    }
}
