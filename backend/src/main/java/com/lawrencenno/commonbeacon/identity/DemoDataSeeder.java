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
        conversations();
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

    private void conversations() {
        UUID boardId = jdbc.queryForObject("SELECT id FROM board WHERE slug='getting-started'", UUID.class);
        UUID alex = jdbc.queryForObject("SELECT id FROM app_user WHERE email='alex.member@example.test'", UUID.class);
        UUID sam = jdbc.queryForObject("SELECT id FROM app_user WHERE email='sam.member@example.test'", UUID.class);
        conversation("first-steps", boardId, alex, sam, "Where should I start in CommonBeacon?",
                "I am new here. What is a useful first step?",
                "Browse a board, ask a clear question, and share what you have already tried.", true);
        conversation("helpful-question", boardId, sam, alex, "What makes a question easy to answer?",
                "I would like to write a question that gives other members enough context.",
                "Include your goal, the steps you tried, and the result you expected.", false);
    }

    private void conversation(String key, UUID boardId, UUID author, UUID responder,
                              String title, String body, String answer, boolean solved) {
        UUID questionId = UUID.nameUUIDFromBytes(("commonbeacon-demo-question-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID replyId = UUID.nameUUIDFromBytes(("commonbeacon-demo-reply-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                INSERT INTO question (id,board_id,author_id,title,body,created_at,updated_at)
                VALUES (?,?,?,?,?,TIMESTAMPTZ '2026-01-01 12:00:00+00',TIMESTAMPTZ '2026-01-01 12:00:00+00')
                ON CONFLICT (id) DO NOTHING
                """, questionId, boardId, author, title, body);
        // Seed a conversation once; later edits, hiding and solution changes belong to the user.
        if (inserted == 0) return;
        jdbc.update("""
                INSERT INTO reply (id,question_id,author_id,body,created_at,updated_at)
                VALUES (?,?,?,?,TIMESTAMPTZ '2026-01-01 12:05:00+00',TIMESTAMPTZ '2026-01-01 12:05:00+00')
                """, replyId, questionId, responder, answer);
        if (solved) jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", replyId, questionId);
    }
}
