package com.lawrencenno.commonbeacon.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Runs inside DemoDataSeeder's opt-in startup transaction. */
final class OnboardingData {
    record Lesson(String key, String boardSlug, String title, String body, String answer, List<String> misconceptions) {}
    private OnboardingData() {}
    private static UUID id(String key) { return UUID.nameUUIDFromBytes(("commonbeacon-demo-" + key).getBytes(StandardCharsets.UTF_8)); }

    static void seed(JdbcTemplate jdbc) {
        // Adopt the original development persistence example, without renaming arbitrary user boards.
        var legacy = jdbc.queryForList("SELECT id FROM board WHERE name='Compose verification' AND description='Fictional persistence verification board.' AND slug LIKE 'compose-%'", UUID.class);
        if (legacy.size() == 1 && jdbc.queryForObject("SELECT count(*) FROM board WHERE slug='using-commonbeacon'", Integer.class) == 0)
            jdbc.update("UPDATE board SET slug='using-commonbeacon',name='Using CommonBeacon',description='Learn how search, saved discussions, and board availability work.',version=version+1 WHERE id=?", legacy.getFirst());
        jdbc.update("""
                INSERT INTO board(id,slug,name,description) VALUES (?,'using-commonbeacon','Using CommonBeacon',
                    'Learn how search, saved discussions, and board availability work.') ON CONFLICT (slug) DO NOTHING
                """, id("board-using-commonbeacon"));
        UUID alex = jdbc.queryForObject("SELECT id FROM app_user WHERE email='alex.member@example.test'", UUID.class);
        UUID sam = jdbc.queryForObject("SELECT id FROM app_user WHERE email='sam.member@example.test'", UUID.class);
        try (var input = OnboardingData.class.getResourceAsStream("/demo/onboarding.json")) {
            if (input == null) throw new IllegalStateException("Missing onboarding lessons");
            for (var lesson : new ObjectMapper().readValue(input, Lesson[].class)) seedLesson(jdbc, lesson, alex, sam);
        } catch (IOException e) { throw new IllegalStateException("Cannot load onboarding lessons", e); }
        cleanOriginalSeedText(jdbc);
    }

    private static void seedLesson(JdbcTemplate jdbc, Lesson lesson, UUID author, UUID responder) {
        if (lesson.misconceptions().size() != 4) throw new IllegalStateException("Every lesson needs four misconceptions");
        UUID marker = id("learning-" + lesson.key() + "-4");
        // The last inserted learning reply marks the completed bundle. Never replay acceptance or edits.
        if (jdbc.queryForObject("SELECT count(*) FROM reply WHERE id=?", Integer.class, marker) != 0) return;
        UUID board = jdbc.queryForObject("SELECT id FROM board WHERE slug=?", UUID.class, lesson.boardSlug());
        UUID question = id("question-" + lesson.key());
        UUID correct = id("reply-" + (lesson.key().equals("hidden-reply") ? "hidden-reply-correct" : lesson.key()));
        boolean adopted = false;
        if (lesson.key().equals("saved-work")) {
            var old = jdbc.queryForList("SELECT id FROM question WHERE board_id=? AND title='Does the container retain my question?' AND body='This question checks container persistence.' AND version=1", UUID.class, board);
            if (old.size() == 1) {
                question = old.getFirst();
                UUID accepted = jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, question);
                if (accepted != null && jdbc.queryForObject("SELECT count(*) FROM reply WHERE id=? AND body='The named volume retains the conversation.' AND version=0 AND visibility='VISIBLE'", Integer.class, accepted) == 1) {
                    correct = accepted; adopted = true;
                } else return;
            }
        }
        var versions = jdbc.queryForList("SELECT version FROM question WHERE id=?", Long.class, question);
        if (!versions.isEmpty() && versions.getFirst() != 0 && !adopted) return;
        var replyVersions = jdbc.queryForList("SELECT version FROM reply WHERE id=?", Long.class, correct);
        if (!replyVersions.isEmpty() && replyVersions.getFirst() != 0) return;
        jdbc.update("""
                INSERT INTO question(id,board_id,author_id,title,body,created_at,updated_at)
                VALUES (?,?,?,?,?,TIMESTAMPTZ '2026-01-03 12:00:00+00',TIMESTAMPTZ '2026-01-03 12:00:00+00')
                ON CONFLICT (id) DO NOTHING
                """, question, board, author, lesson.title(), lesson.body());
        jdbc.update("""
                INSERT INTO reply(id,question_id,author_id,body,created_at,updated_at)
                VALUES (?,?,?,?,TIMESTAMPTZ '2026-01-03 12:05:00+00',TIMESTAMPTZ '2026-01-03 12:05:00+00')
                ON CONFLICT (id) DO NOTHING
                """, correct, question, responder, lesson.answer());
        jdbc.update("UPDATE reply SET body=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND body<>?", lesson.answer(), correct, lesson.answer());
        for (int i = 0; i < 4; i++) {
            jdbc.update("""
                    INSERT INTO reply(id,question_id,author_id,body,created_at,updated_at)
                    VALUES (?,?,?,?,TIMESTAMPTZ '2026-01-03 12:10:00+00',TIMESTAMPTZ '2026-01-03 12:10:00+00')
                    """, id("learning-" + lesson.key() + "-" + (i + 1)), question, i % 2 == 0 ? author : responder, lesson.misconceptions().get(i));
        }
        jdbc.update("UPDATE question SET title=?,body=?,accepted_reply_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", lesson.title(), lesson.body(), correct, question);
    }

    private static void cleanOriginalSeedText(JdbcTemplate jdbc) {
        // One-time wording cleanup is restricted to known seed IDs and original strings.
        jdbc.update("UPDATE knowledge_article SET body=replace(body,'Fictional ',''),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id IN (?,?) AND body IN (?,?)",
                id("article-demo-writing-a-helpful-question"), id("article-demo-review-checklist"),
                "Fictional community guide: describe your goal, explain what you tried, and share the result. Keep passwords and personal details out of public questions.",
                "Fictional administrator draft: review the context, explain the decision, and check public visibility after a moderation action.");
        jdbc.update("UPDATE reply SET body=replace(body,'Fictional ',''),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND body='Fictional outdated advice retained privately for a moderation demonstration.'", id("reply-hidden-reply"));
        jdbc.update("UPDATE content_report SET reason=replace(reason,'Fictional ',''),resolution_note=replace(resolution_note,'Fictional ',''),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id IN (?,?) AND reason IN ('Fictional training report: please review whether this question needs clarification.','Fictional training report: this reply contains outdated guidance.')",
                id("report-review-question"), id("report-hidden-reply"));
        jdbc.update("UPDATE moderation_action SET reason=replace(reason,'Fictional ','') WHERE id=? AND reason='Fictional review: hide outdated advice until it is checked.'", id("action-hidden-reply"));
    }
}
