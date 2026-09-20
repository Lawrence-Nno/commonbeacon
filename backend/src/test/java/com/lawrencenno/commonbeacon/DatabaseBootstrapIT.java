package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.AppUser;
import com.lawrencenno.commonbeacon.identity.UserRole;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class DatabaseBootstrapIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory entityManagerFactory;
    @LocalServerPort int port;

    @Test
    void healthReportsUpWithoutExposingDatabaseDetails() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details", "jdbc:");
        }
    }

    @Test
    void migrationIsAppliedOnceAndCanBeRunAgainWithoutChanges() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9') AND success",
                Integer.class)).isEqualTo(9);
    }

    @Test
    void migratedUserCanBeReadThroughTheValidatedJpaMapping() {
        var id = insertUser(uniqueEmail(), "MEMBER");
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            var user = entityManager.find(AppUser.class, id);
            assertThat(user.getId()).isEqualTo(id);
            assertThat(user.getDisplayName()).isEqualTo("Test Member");
            assertThat(user.getRole()).isEqualTo(UserRole.MEMBER);
            assertThat(user.getCreatedAt()).isNotNull();
        } finally {
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
    }

    @Test
    void duplicateNormalizedEmailIsRejectedByTheDatabase() {
        var email = uniqueEmail();
        var id = insertUser(email, "MEMBER");
        try {
            assertThatThrownBy(() -> insertUser(email, "MEMBER"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
    }

    @Test
    void databaseRejectsUnnormalizedEmails() {
        assertThatThrownBy(() -> insertUser(" MixedCase@example.test ", "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownRoles() {
        assertThatThrownBy(() -> insertUser(uniqueEmail(), "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsEmptyPasswordHash() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), uniqueEmail(), "Test Member", " "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String email, String role) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, email, display_name, password_hash, role)
                VALUES (?, ?, ?, ?, ?)
                """, id, email, "Test Member", "test-only-placeholder-not-a-login-credential", role);
        return id;
    }

    private String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
