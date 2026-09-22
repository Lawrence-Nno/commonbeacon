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
@Import({PostgresTestConfiguration.class, ImportedIdentityIT.ProbeController.class})
@org.springframework.test.context.ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.MethodName.class)
class ImportedIdentityIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired com.lawrencenno.commonbeacon.identity.ImportedAuthors importedAuthors;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired IdentityService identity;
    @Autowired com.lawrencenno.commonbeacon.transfer.access.TransferAccess transferAccess;
    @Autowired com.lawrencenno.commonbeacon.transfer.job.TransferJobs jobs;
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

    UUID imported(UUID instance, UUID source, boolean contacts, String email) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
            importedAuthors.create(instance, source, "Historical Author", java.time.Instant.parse("2020-01-01T00:00:00Z"), contacts, email));
    }

    @Test void importedContactsNeverMatchAccountsAndAttributionRemainsPubliclySafe() throws Exception {
        String email = email();
        UUID instance = UUID.randomUUID(), source = UUID.randomUUID();
        try (var browser = new Browser()) {
            browser.register(email);
            UUID active = jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, email);
            var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", active);
            UUID first = imported(instance, source, true, email);
            UUID second = imported(instance, UUID.randomUUID(), true, email);
            assertThat(first).isNotEqualTo(active).isNotEqualTo(second).isNotEqualTo(source);
            assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", active)).isEqualTo(before);
            assertThat(jdbc.queryForMap("SELECT email,password_hash,role,account_state FROM app_user WHERE id=?", first))
                .containsEntry("email", null).containsEntry("password_hash", null)
                .containsEntry("role", "MEMBER").containsEntry("account_state", "IMPORTED_INACTIVE");
            assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
            assertThat(json.readTree(browser.get("/api/v1/auth/me").body()).get("id").asText()).isEqualTo(active.toString());
            UUID board = UUID.randomUUID(), question = UUID.randomUUID();
            jdbc.update("INSERT INTO board(id,slug,name,description) VALUES (?,?,'Imported board','Attribution test')", board, board.toString());
            jdbc.update("INSERT INTO question(id,board_id,author_id,title,body) VALUES (?,?,?,'Imported question','Historical content')", question, board, first);
            var response = browser.get("/api/v1/questions/" + question);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Historical Author", first.toString()).doesNotContain(email, "source_email", "password", "source_instance");
            assertThat(browser.get("/api/v1/testing/owned/" + first).statusCode()).isEqualTo(403);
        }
    }

    @Test void importedMappingIsImmutableUniqueAndContactRetentionIsOptIn() throws Exception {
        UUID instance = UUID.randomUUID(), source = UUID.randomUUID();
        String contact = email();
        UUID id = imported(instance, source, false, contact);
        assertThat(jdbc.queryForObject("SELECT source_email FROM imported_author WHERE local_user_id=?", String.class, id)).isNull();
        long before = jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imported(instance, source, true, contact))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class)).isEqualTo(before);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE imported_author SET source_email=? WHERE local_user_id=?", contact, id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (String change : List.of("role='ADMINISTRATOR'", "role='MODERATOR'", "password_hash='forged'",
                "email='forged@example.test'", "account_state='ACTIVE',email='claim@example.test',password_hash='forged'")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET " + change + " WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        try (var browser = new Browser()) {
            assertThat(browser.login(contact, PASSWORD).statusCode()).isEqualTo(401);
            assertThat(browser.register(contact).statusCode()).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, contact)).isNotEqualTo(id);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.create(id,
            com.lawrencenno.commonbeacon.transfer.job.TransferJob.Kind.PERSONAL_EXPORT, UUID.randomUUID(), "a".repeat(64)))
            .hasMessage("FORBIDDEN");
    }

    @Test void inactiveStateRevokesExistingSessionServicesAndWorkerLease() throws Exception {
        String email = email();
        try (var browser = new Browser()) {
            browser.register(email);
            assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
            UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, email);
            var job = jobs.create(id, com.lawrencenno.commonbeacon.transfer.job.TransferJob.Kind.PERSONAL_EXPORT,
                UUID.randomUUID(), "a".repeat(64));
            var lease = jobs.claim(UUID.randomUUID()).orElseThrow();
            jdbc.update("UPDATE app_user SET account_state='IMPORTED_INACTIVE',email=NULL,password_hash=NULL WHERE id=?", id);
            assertThat(jdbc.queryForObject("SELECT auth_revision FROM app_user WHERE id=?", Long.class, id)).isEqualTo(1L);
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(email, "unused", List.of());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> identity.current(auth))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> transferAccess.current(auth, false))
                .isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
            assertThat(jobs.checkpoint(lease.lease(), 1)).isFalse();
            assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?", String.class, job.id())).isEqualTo("AUTHORIZATION_REVOKED");
            assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
            assertThat(browser.get("/api/v1/testing/owned/" + id).statusCode()).isEqualTo(401);
            assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(401);
        }
    }

}
