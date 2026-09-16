package com.lawrencenno.commonbeacon.identity;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local opt-in. Existing identities, credentials and boards are never rewritten. */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "commonbeacon.demo.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    public DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder encoder,
                          @Value("${commonbeacon.demo.password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (password.isBlank() || password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Demo seeding requires DEMO_PASSWORD with 12-128 characters.");
        }
        var hash = encoder.encode(password);
        account("alex.member@example.test", "Alex River", "MEMBER", hash);
        account("sam.member@example.test", "Sam Reed", "MEMBER", hash);
        account("morgan.moderator@example.test", "Morgan Vale", "MODERATOR", hash);
        account("avery.admin@example.test", "Avery Stone", "ADMINISTRATOR", hash);
        board("getting-started", "Getting started", "Find your footing, share first steps, and learn the essentials.");
        board("product-help", "Product help", "A shared place for product questions and helpful experience.");
    }

    private void account(String email, String name, String role, String hash) {
        jdbc.update("""
                INSERT INTO app_user (id, email, display_name, password_hash, role)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), email, name, hash, role);
    }

    private void board(String slug, String name, String description) {
        jdbc.update("""
                INSERT INTO board (id, slug, name, description)
                VALUES (?, ?, ?, ?) ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), slug, name, description);
    }
}
