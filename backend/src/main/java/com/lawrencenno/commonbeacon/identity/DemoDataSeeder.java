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
        operations();
        OnboardingData.seed(jdbc);
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

    private static UUID demoId(String key) {
        return UUID.nameUUIDFromBytes(("commonbeacon-demo-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void operations() {
        UUID board = jdbc.queryForObject("SELECT id FROM board WHERE slug='product-help'", UUID.class);
        UUID alex = jdbc.queryForObject("SELECT id FROM app_user WHERE email='alex.member@example.test'", UUID.class);
        UUID sam = jdbc.queryForObject("SELECT id FROM app_user WHERE email='sam.member@example.test'", UUID.class);
        UUID moderator = jdbc.queryForObject("SELECT id FROM app_user WHERE email='morgan.moderator@example.test'", UUID.class);
        UUID admin = jdbc.queryForObject("SELECT id FROM app_user WHERE email='avery.admin@example.test'", UUID.class);
        article("demo-writing-a-helpful-question", "Writing a helpful question", "PUBLISHED", admin,
                "community guide: describe your goal, explain what you tried, and share the result. Keep passwords and personal details out of public questions.");
        article("demo-review-checklist", "Community review checklist", "DRAFT", admin,
                "administrator draft: review the context, explain the decision, and check public visibility after a moderation action.");
        moderationExample("review-question", board, alex, sam, moderator, false);
        moderationExample("hidden-reply", board, sam, alex, moderator, true);
    }

    private void article(String slug, String title, String status, UUID author, String body) {
        jdbc.update("""
                INSERT INTO knowledge_article(id,slug,title,body,status,author_id,created_at,updated_at,published_at)
                VALUES (?,?,?,?,?,?,TIMESTAMPTZ '2026-01-02 12:00:00+00',TIMESTAMPTZ '2026-01-02 12:00:00+00',
                    CASE WHEN ?='PUBLISHED' THEN TIMESTAMPTZ '2026-01-02 12:00:00+00' ELSE NULL END)
                ON CONFLICT DO NOTHING
                """, demoId("article-" + slug), slug, title, body, status, author, status);
    }

    private void moderationExample(String key, UUID board, UUID author, UUID responder, UUID moderator, boolean hiddenReply) {
        UUID question = demoId("question-" + key), reply = demoId("reply-" + key);
        int inserted = jdbc.update("""
                INSERT INTO question(id,board_id,author_id,title,body,created_at,updated_at)
                VALUES (?,?,?,?,?,TIMESTAMPTZ '2026-01-02 12:00:00+00',TIMESTAMPTZ '2026-01-02 12:00:00+00')
                ON CONFLICT (id) DO NOTHING
                """, question, board, author,
                hiddenReply ? "How should I check advice before following it?" : "Can someone clarify the community posting guidelines?",
                "demonstration question. Please explain the community guidance with a practical example.");
        // The parent is the once-only marker for the whole example. Never replay moderation on restart.
        if (inserted == 0) return;
        if (!hiddenReply) {
            jdbc.update("""
                    INSERT INTO content_report(id,reporter_id,question_id,reason,created_at,updated_at)
                    VALUES (?,?,?,'training report: please review whether this question needs clarification.',
                        TIMESTAMPTZ '2026-01-02 12:05:00+00',TIMESTAMPTZ '2026-01-02 12:05:00+00')
                    """, demoId("report-" + key), responder, question);
            return;
        }
        jdbc.update("""
                INSERT INTO reply(id,question_id,author_id,body,visibility,version,created_at,updated_at)
                VALUES (?,?,?,'outdated advice retained privately for a moderation demonstration.','HIDDEN',1,
                    TIMESTAMPTZ '2026-01-02 12:05:00+00',TIMESTAMPTZ '2026-01-02 12:15:00+00')
                """, reply, question, responder);
        jdbc.update("""
                INSERT INTO content_report(id,reporter_id,reply_id,reason,status,version,created_at,updated_at,
                    resolver_id,resolved_at,resolution_decision,resolution_note)
                VALUES (?,?,?,'training report: this reply contains outdated guidance.','RESOLVED',1,
                    TIMESTAMPTZ '2026-01-02 12:10:00+00',TIMESTAMPTZ '2026-01-02 12:15:00+00',?,
                    TIMESTAMPTZ '2026-01-02 12:15:00+00','HIDE','review: hide outdated advice until it is checked.')
                """, demoId("report-" + key), author, reply, moderator);
        jdbc.update("""
                INSERT INTO moderation_action(id,actor_id,reply_id,action,reason,created_at)
                VALUES (?,?,?,'HIDE','review: hide outdated advice until it is checked.',TIMESTAMPTZ '2026-01-02 12:15:00+00')
                """, demoId("action-" + key), moderator, reply);
    }
}
