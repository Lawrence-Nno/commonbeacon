package com.lawrencenno.commonbeacon;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

class MilestoneUpgradeIT {
    private PostgreSQLContainer database() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2"));
    }
    private Flyway flyway(PostgreSQLContainer db, String target) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target(target).load();
    }
    private Map<String, List<String>> originalRows(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("app_user", "board", "question", "reply"))
            result.put(table, jdbc.queryForList("SELECT (to_jsonb(t)-'search_vector'-'auth_revision')::text FROM " + table + " t ORDER BY id", String.class));
        return result;
    }
    private void validatesCurrentApplication(PostgreSQLContainer db) {
        try (var app = new SpringApplicationBuilder(CommonBeaconApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
                "--commonbeacon.demo.enabled=false", "--spring.jpa.hibernate.ddl-auto=validate")) {
            assertThat(app.getBean(jakarta.persistence.EntityManagerFactory.class).isOpen()).isTrue();
            assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(11);
        }
    }
    @Test void cleanDatabaseMigratesAndBootsWithHibernateValidation() {
        try (var db = database()) {
            db.start();
            validatesCurrentApplication(db);
            assertThat(flyway(db, "latest").migrate().migrationsExecuted).isZero();
        }
    }
    @Test void populatedV9DatabaseRetainsEveryDomainTableWhenTransferTablesAreAdded() {
        try(var db=database()) {
            db.start();flyway(db,"9").migrate();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
            UUID user=UUID.randomUUID(),board=UUID.randomUUID(),question=UUID.randomUUID(),reply=UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,'transfer-upgrade@example.test','Upgrade owner','preserved-test-hash','ADMINISTRATOR')",user);
            jdbc.update("INSERT INTO board(id,slug,name,description) VALUES (?,'transfer-upgrade','Upgrade board','Preserve this board')",board);
            jdbc.update("INSERT INTO question(id,board_id,author_id,title,body) VALUES (?,?,?,'Preserved question','Preserve this question content')",question,board,user);
            jdbc.update("INSERT INTO reply(id,question_id,author_id,body) VALUES (?,?,?,'Preserve this answer')",reply,question,user);
            jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?",reply,question);
            jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,'Preserve this report')",UUID.randomUUID(),user,question);
            jdbc.update("INSERT INTO moderation_action(id,actor_id,question_id,action,reason) VALUES (?,?,?,'RESTORE','Preserve source action')",UUID.randomUUID(),user,question);
            jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,author_id) VALUES (?,'transfer-upgrade','Preserved article','Preserve article body',?)",UUID.randomUUID(),user);
            var tables=List.of("app_user","board","question","reply","content_report","moderation_action","knowledge_article");
            var before=new LinkedHashMap<String,List<String>>();
            for(var table:tables)before.put(table,jdbc.queryForList("SELECT (to_jsonb(t)-'auth_revision')::text FROM "+table+" t ORDER BY id",String.class));
            var upgrade=flyway(db,"latest");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);upgrade.validate();
            validatesCurrentApplication(db);
            for(var table:tables)assertThat(jdbc.queryForList("SELECT (to_jsonb(t)-'auth_revision')::text FROM "+table+" t ORDER BY id",String.class)).isEqualTo(before.get(table));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_job",Integer.class)).isZero();
        }
    }
    @Test void milestoneAFixturesSurviveUpgradeWithSelectionsConstraintsAndSearchVectors() {
        try (var db = database()) {
            db.start(); assertThat(flyway(db, "5").migrate().migrationsExecuted).isEqualTo(5);
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID owner = UUID.randomUUID(), moderator = UUID.randomUUID(), board = UUID.randomUUID(), archived = UUID.randomUUID();
            UUID solved = UUID.randomUUID(), hidden = UUID.randomUUID(), archivedQuestion = UUID.randomUUID();
            UUID answer = UUID.randomUUID(), hiddenReply = UUID.randomUUID(), archivedReply = UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,?,'preserved-test-hash','MEMBER'),(?,?,?,'preserved-operator-hash','MODERATOR')", owner, "upgrade.member@example.test", "Upgrade Member", moderator, "upgrade.moderator@example.test", "Upgrade Moderator");
            jdbc.update("INSERT INTO board(id,slug,name,description,archived,version) VALUES (?,'upgrade-active','Active','Preserved active board',false,2),(?,'upgrade-archived','Archived','Preserved archived board',true,3)", board, archived);
            jdbc.update("INSERT INTO question(id,board_id,author_id,title,body,visibility,version) VALUES (?,?,?,'Telescope setup question','Preserved telescope setup instructions','VISIBLE',4),(?,?,?,'Private telescope question','Preserved private telescope instructions','HIDDEN',2),(?,?,?,'Archived telescope question','Preserved archived board instructions','VISIBLE',1)", solved, board, owner, hidden, board, owner, archivedQuestion, archived, owner);
            jdbc.update("INSERT INTO reply(id,question_id,author_id,body,visibility,version) VALUES (?,?,?,'Preserved accepted answer','VISIBLE',2),(?,?,?,'Preserved hidden reply','HIDDEN',1),(?,?,?,'Preserved archived accepted answer','VISIBLE',0)", answer, solved, moderator, hiddenReply, hidden, owner, archivedReply, archivedQuestion, moderator);
            jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", answer, solved);
            jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", archivedReply, archivedQuestion);
            var before = originalRows(jdbc);
            var upgrade = flyway(db, "latest");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(6);
            upgrade.validate();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(originalRows(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE visibility='VISIBLE' AND search_vector @@ websearch_to_tsquery('english','telescopes')", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE search_vector IS NOT NULL", Integer.class)).isEqualTo(3);
            assertThatThrownBy(() -> jdbc.update("UPDATE question SET accepted_reply_id=? WHERE id=?", archivedReply, solved)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO content_report(id,reporter_id,reason) VALUES (?,?,'Missing target')", UUID.randomUUID(), owner)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,'Preserved content needs review')", UUID.randomUUID(), owner, solved);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO content_report(id,reporter_id,question_id,reason) VALUES (?,?,?,'Duplicate open report')", UUID.randomUUID(), owner, solved)).isInstanceOf(DataIntegrityViolationException.class);
            UUID article = UUID.randomUUID();
            jdbc.update("INSERT INTO knowledge_article(id,slug,title,body,status,author_id,published_at) VALUES (?,'upgrade-guide','Telescope guide','New telescope article after upgrade','PUBLISHED',?,CURRENT_TIMESTAMP)", article, owner);
            assertThat(jdbc.queryForObject("SELECT search_vector @@ websearch_to_tsquery('english','telescopes') FROM knowledge_article WHERE id=?", Boolean.class, article)).isTrue();
            assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_article SET status='DRAFT' WHERE id=?", article)).isInstanceOf(DataIntegrityViolationException.class);
            validatesCurrentApplication(db);
            assertThat(originalRows(jdbc)).isEqualTo(before);
        }
    }
}
