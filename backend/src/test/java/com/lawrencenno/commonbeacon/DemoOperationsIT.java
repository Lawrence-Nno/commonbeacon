package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.DemoDataSeeder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"commonbeacon.demo.enabled=true", "commonbeacon.demo.password=demo-operations-test-42"})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("local")
@Transactional
class DemoOperationsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataSeeder seeder;
    UUID id(String key) { return UUID.nameUUIDFromBytes(("commonbeacon-demo-" + key).getBytes(StandardCharsets.UTF_8)); }
    Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("app_user", "board", "question", "reply", "content_report", "moderation_action", "knowledge_article"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    @Test void initialExamplesHaveCoherentVisibilityResolutionAuditAndSearchState() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_report WHERE status='OPEN'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_report WHERE status='RESOLVED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM content_report c JOIN reply r ON r.id=c.reply_id
                JOIN moderation_action a ON a.reply_id=r.id
                WHERE c.status='RESOLVED' AND c.resolution_decision='HIDE' AND r.visibility='HIDDEN'
                    AND a.action='HIDE' AND a.actor_id=c.resolver_id AND a.reason=c.resolution_note
                    AND a.created_at=c.resolved_at AND c.created_at<c.resolved_at AND r.updated_at=c.resolved_at
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_article WHERE status='PUBLISHED' AND search_vector @@ plainto_tsquery('english','helpful question')", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_article WHERE status='DRAFT' AND published_at IS NULL", Integer.class)).isEqualTo(1);
        var before = snapshot();
        seeder.run(new DefaultApplicationArguments()); seeder.run(new DefaultApplicationArguments());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void repeatedSeedingPreservesAllLaterHumanChanges() {
        UUID moderator = jdbc.queryForObject("SELECT id FROM app_user WHERE email='morgan.moderator@example.test'", UUID.class);
        jdbc.update("UPDATE app_user SET password_hash='changed-local-hash',display_name='Changed name' WHERE email='alex.member@example.test'");
        jdbc.update("UPDATE knowledge_article SET body='Human edited article body.',version=version+1 WHERE slug='demo-review-checklist'");
        jdbc.update("UPDATE knowledge_article SET status='ARCHIVED',version=version+1 WHERE slug='demo-writing-a-helpful-question'");
        jdbc.update("UPDATE content_report SET status='RESOLVED',resolver_id=?,resolved_at=CURRENT_TIMESTAMP,resolution_decision='DISMISS',resolution_note='Human decision retained.',version=version+1 WHERE id=?", moderator, id("report-review-question"));
        jdbc.update("UPDATE reply SET visibility='VISIBLE',body='Human corrected advice.',version=version+1 WHERE id=?", id("reply-hidden-reply"));
        jdbc.update("INSERT INTO moderation_action(id,actor_id,reply_id,action,reason) VALUES (?,?,?,'RESTORE','Human reviewed and restored.')", UUID.randomUUID(), moderator, id("reply-hidden-reply"));
        jdbc.update("UPDATE question SET title='Human revised question title',accepted_reply_id=? WHERE id=?", id("reply-hidden-reply"), id("question-hidden-reply"));
        var before = snapshot();
        seeder.run(new DefaultApplicationArguments()); seeder.run(new DefaultApplicationArguments());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void threeBoardsHaveNineLessonsAndFortyFiveVisibleAnswersWithCorrectSelections() throws Exception {
        try (var input = getClass().getResourceAsStream("/demo/onboarding.json")) {
            var lessons = new tools.jackson.databind.ObjectMapper().readTree(input);
            assertThat(lessons.size()).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM board", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM question", Integer.class)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE visibility='VISIBLE'", Integer.class)).isEqualTo(45);
            for (var lesson : lessons) {
                var question = jdbc.queryForMap("SELECT q.id,q.accepted_reply_id,r.body FROM question q JOIN board b ON b.id=q.board_id JOIN reply r ON r.id=q.accepted_reply_id AND r.question_id=q.id AND r.visibility='VISIBLE' WHERE q.title=? AND b.slug=?", lesson.get("title").asText(), lesson.get("boardSlug").asText());
                assertThat(question.get("body")).isEqualTo(lesson.get("answer").asText());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=? AND visibility='VISIBLE'", Integer.class, question.get("id"))).isEqualTo(5);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=? AND body LIKE 'Common misconception (incorrect):%'", Integer.class, question.get("id"))).isEqualTo(4);
            }
            for (String table : List.of("board", "question", "reply", "content_report", "moderation_action", "knowledge_article"))
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " t WHERE lower(row_to_json(t)::text) LIKE '%fictional%'", Integer.class)).isZero();
        }
    }

    @Test void adoptsOriginalPersistenceBoardAndConversationWithoutDuplicates() {
        UUID board = jdbc.queryForObject("SELECT id FROM board WHERE slug='using-commonbeacon'", UUID.class);
        UUID seeded = id("question-saved-work");
        jdbc.update("UPDATE question SET accepted_reply_id=NULL WHERE id=?", seeded);
        jdbc.update("DELETE FROM reply WHERE question_id=?", seeded);
        jdbc.update("DELETE FROM question WHERE id=?", seeded);
        jdbc.update("UPDATE board SET slug=?,name='Compose verification',description='Fictional persistence verification board.' WHERE id=?", "compose-" + UUID.randomUUID(), board);
        UUID question = UUID.randomUUID(), reply = UUID.randomUUID();
        UUID author = jdbc.queryForObject("SELECT id FROM app_user WHERE email='alex.member@example.test'", UUID.class);
        jdbc.update("INSERT INTO question(id,board_id,author_id,title,body,version) VALUES (?,?,?,'Does the container retain my question?','This question checks container persistence.',1)", question, board, author);
        jdbc.update("INSERT INTO reply(id,question_id,author_id,body) VALUES (?,?,?,'The named volume retains the conversation.')", reply, question, author);
        jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", reply, question);
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT id FROM board WHERE slug='using-commonbeacon'", UUID.class)).isEqualTo(board);
        assertThat(jdbc.queryForObject("SELECT accepted_reply_id FROM question WHERE id=?", UUID.class, question)).isEqualTo(reply);
        assertThat(jdbc.queryForObject("SELECT title FROM question WHERE id=?", String.class, question)).isEqualTo("Will my saved questions and answers survive signing out?");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE board_id=?", Integer.class, board)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reply WHERE question_id=?", Integer.class, question)).isEqualTo(5);
        var before = snapshot(); seeder.run(new DefaultApplicationArguments());
        assertThat(snapshot()).isEqualTo(before);
    }
}
