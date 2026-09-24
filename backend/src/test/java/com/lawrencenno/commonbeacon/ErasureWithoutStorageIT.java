package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import com.lawrencenno.commonbeacon.offboarding.ErasureService;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false","commonbeacon.erasure.initial-delay-ms=20","commonbeacon.erasure.interval-ms=20"})
@Import(PostgresTestConfiguration.class)
class ErasureWithoutStorageIT {
    @Autowired JdbcTemplate jdbc;@Autowired ErasureService erasure;
    @Test void scheduledErasureCompletesWithoutAnyArtifactStoreBean() {
        UUID member=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Delete me','test-hash','MEMBER')",member,member+"@example.test");
        var preview=erasure.preview(member,ErasureService.Scope.ACCOUNT);UUID id=UUID.fromString(preview.path("id").asText());
        erasure.confirm(member,id,preview.path("digest").asText(),"DELETE MY ACCOUNT",Set.of("IRREVERSIBLE","RETAINED_DATA","ALL_TRANSFER_FILES","BACKUP_RETENTION"),"t".repeat(43),actor->{});
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(erasure.status(id,"t".repeat(43)).path("state").asText()).isEqualTo("COMPLETED"));
        assertThat(erasure.active()).isFalse();assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,member)).isEqualTo("ERASED");
    }
}
