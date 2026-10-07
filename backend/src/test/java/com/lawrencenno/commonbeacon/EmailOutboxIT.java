package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.*;
import com.lawrencenno.commonbeacon.identity.EmailChallenges.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EmailOutboxIT {
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;@Autowired PasswordEncoder passwords;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer database;
    final List<UUID> users=new ArrayList<>();
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    EmailChallengesIT.MutableClock clock;EmailChallenges challenges;EmailOutbox outbox;OutboxCrypto crypto;EmailIdentity identity;
    @BeforeEach void setup(){clock=new EmailChallengesIT.MutableClock(Instant.parse("2030-01-01T00:00:00Z"));crypto=new OutboxCrypto(true,"a","a="+key);challenges=new EmailChallenges(jdbc,manager,clock,new SecureRandom(),passwords);outbox=new EmailOutbox(jdbc,manager,crypto,clock);identity=new EmailIdentity(challenges,outbox,crypto);}
    @AfterEach void cleanup(){jdbc.execute("TRUNCATE erasure_job,erasure_tombstone,email_transport_budget CASCADE");for(UUID id:users)jdbc.update("DELETE FROM app_user WHERE id=?",id);users.clear();}
    UUID user(boolean verified){UUID id=UUID.randomUUID();users.add(id);jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,email_verified_at) VALUES (?,?,'Outbox member',?,?)",id,id+"@example.test",passwords.encode("outbox-old-password"),verified?java.sql.Timestamp.from(clock.instant().minusSeconds(1)):null);return id;}
    Issued issue(UUID id,Purpose purpose){var c=new AtomicReference<Issued>();assertThat(challenges.issue(id,purpose,(purpose==Purpose.EMAIL_CHANGE?"new-":"")+id+"@example.test",()->true,x->{outbox.challenge(x);c.set(x);})).isTrue();return c.get();}
    Map<String,Object> row(UUID id){return jdbc.queryForMap("SELECT * FROM email_outbox WHERE event_id=? AND message_type IN ('VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE')",id);}
    @Test @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void persistedPayloadIsEncryptedDeduplicatedAndSurvivesServiceAndDatabaseRestart(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);var row=row(c.id());
        assertThat(new String((byte[])row.get("ciphertext"),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(c.token(),c.email());
        new TransactionTemplate(manager).executeWithoutResult(tx->outbox.challenge(c));assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE subject_id=?",Long.class,id)).isEqualTo(1);
        database.getDockerClient().restartContainerCmd(database.getContainerId()).exec();
        var inspected=database.getDockerClient().inspectContainerCmd(database.getContainerId()).exec();
        String mapped=inspected.getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec();
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://"+database.getHost()+":"+mapped+"/"+database.getDatabaseName(),database.getUsername(),database.getPassword());
        jdbc=new JdbcTemplate(source);manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->assertThat(jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1));
        var restarted=new EmailOutbox(jdbc,manager,new OutboxCrypto(true,"a","a="+key),clock);var lease=restarted.claim(UUID.randomUUID()).orElseThrow();var payload=restarted.prepare(lease);
        assertThat(payload.recipient()).isEqualTo(c.email());assertThat(payload.token()).isEqualTo(c.token());assertThat(payload.toString()).isEqualTo("MailPayload[REDACTED]");
    }
    @Test void disabledOrFailedOutboxLeavesOriginalLinkAndGenerationUsable(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);clock.value.set(clock.instant().plusSeconds(60));
        var disabled=new EmailIdentity(challenges,outbox,new OutboxCrypto(false,"",""));
        assertThatThrownBy(()->disabled.issue(id,Purpose.VERIFICATION,c.email(),()->true)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        assertThatThrownBy(()->challenges.issue(id,Purpose.VERIFICATION,c.email(),()->true,x->{outbox.challenge(x);throw new IllegalStateException("TEST_ROLLBACK");})).hasMessage("TEST_ROLLBACK");
        assertThat(jdbc.queryForObject("SELECT verification_generation FROM app_user WHERE id=?",Long.class,id)).isEqualTo(1);assertThat(row(c.id()).get("state")).isEqualTo("QUEUED");
        identity.consume(c.purpose(),c.token(),null);assertThat(row(c.id())).containsEntry("state","CANCELLED").containsEntry("ciphertext",null);
    }
    @Test void outerRollbackLeavesNeitherChallengeNorIntent(){
        UUID id=user(false);new TransactionTemplate(manager).executeWithoutResult(tx->{assertThat(identity.issue(id,Purpose.VERIFICATION,id+"@example.test",()->true)).isTrue();tx.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=?",Long.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE subject_id=?",Long.class,id)).isZero();
    }
    @Test void transientRetryKeepsExactCiphertextNonceTokenAndExpiry(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);var before=row(c.id());var lease=outbox.claim(UUID.randomUUID()).orElseThrow();assertThat(outbox.prepare(lease).token()).isEqualTo(c.token());
        assertThat(outbox.failed(lease,EmailOutbox.Failure.TRANSIENT)).isTrue();var after=row(c.id());assertThat(after.get("ciphertext")).isEqualTo(before.get("ciphertext"));assertThat(after.get("nonce")).isEqualTo(before.get("nonce"));assertThat(after.get("expires_at")).isEqualTo(before.get("expires_at"));
        assertThat(outbox.accepted(lease,UUID.randomUUID())).isFalse();clock.value.set(((java.sql.Timestamp)after.get("next_attempt_at")).toInstant());
        var retry=outbox.claim(UUID.randomUUID()).orElseThrow();assertThat(outbox.prepare(retry).token()).isEqualTo(c.token());assertThat(outbox.accepted(retry,UUID.randomUUID())).isTrue();
        assertThat(row(c.id())).containsEntry("state","ACCEPTED").containsEntry("ciphertext",null).containsEntry("nonce",null);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM email_challenge WHERE id=?",java.sql.Timestamp.class,c.id())).isNull();
    }
    @Test void resendCancelsEvenClaimedPayloadAndFencesLateAcceptance(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);var lease=outbox.claim(UUID.randomUUID()).orElseThrow();clock.value.set(clock.instant().plusSeconds(60));issue(id,Purpose.VERIFICATION);
        assertThat(outbox.prepare(lease)).isNull();assertThat(outbox.accepted(lease,UUID.randomUUID())).isFalse();assertThat(row(c.id())).containsEntry("state","CANCELLED").containsEntry("ciphertext",null);
    }
    @Test void currentAddressAndGenerationAreRecheckedBeforeSending(){
        UUID id=user(false);var c=issue(id,Purpose.PASSWORD_RESET);var lease=outbox.claim(UUID.randomUUID()).orElseThrow();jdbc.update("UPDATE app_user SET email=? WHERE id=?","changed-"+id+"@example.test",id);
        assertThat(outbox.prepare(lease)).isNull();assertThat(row(c.id())).containsEntry("state","CANCELLED");
        UUID other=user(false);var stale=issue(other,Purpose.VERIFICATION);jdbc.update("UPDATE app_user SET verification_generation=verification_generation+1 WHERE id=?",other);assertThat(outbox.claim(UUID.randomUUID())).isEmpty();assertThat(row(stale.id()).get("ciphertext")).isNull();
    }
    @Test void securityNoticesKeepOldAndNewRecipientsAfterEmailCompletion(){
        UUID id=user(true);var c=issue(id,Purpose.EMAIL_CHANGE);identity.consume(c.purpose(),c.token(),null);
        var notices=jdbc.queryForList("SELECT message_type FROM email_outbox WHERE subject_id=? AND state='QUEUED'",String.class,id);assertThat(notices).containsExactlyInAnyOrder("EMAIL_CHANGED_OLD","EMAIL_CHANGED_NEW");
        Set<String> recipients=new HashSet<>();for(int i=0;i<2;i++){var lease=outbox.claim(UUID.randomUUID()).orElseThrow();var payload=outbox.prepare(lease);assertThat(payload.token()).isEmpty();recipients.add(payload.recipient());outbox.accepted(lease,UUID.randomUUID());}
        assertThat(recipients).containsExactlyInAnyOrder(id+"@example.test","new-"+id+"@example.test");
    }
    @Test void passwordChangeAndNoticeRollbackTogetherWhenEncryptionUnavailable(){
        UUID id=user(false);var c=issue(id,Purpose.PASSWORD_RESET);var before=jdbc.queryForMap("SELECT password_hash,auth_epoch FROM app_user WHERE id=?",id);
        var unavailable=new EmailOutbox(jdbc,manager,new OutboxCrypto(false,"",""),clock);
        assertThatThrownBy(()->challenges.consume(c.purpose(),c.token(),"outbox-new-password",unavailable::completed)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        assertThat(jdbc.queryForMap("SELECT password_hash,auth_epoch FROM app_user WHERE id=?",id)).isEqualTo(before);assertThat(row(c.id()).get("state")).isEqualTo("QUEUED");identity.consume(c.purpose(),c.token(),"outbox-new-password");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE subject_id=? AND message_type='PASSWORD_CHANGED'",Long.class,id)).isEqualTo(1);
    }
    @Test void suspensionAndErasurePurgePayloadImmediately(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",id);assertThat(row(c.id())).containsEntry("ciphertext",null).containsEntry("state","CANCELLED");
        UUID erased=user(false);var reset=issue(erased,Purpose.PASSWORD_RESET);jdbc.update("UPDATE app_user SET account_state='ERASED',email=NULL,password_hash=NULL,display_name='Deleted member' WHERE id=?",erased);
        assertThat(row(reset.id())).containsEntry("ciphertext",null).containsEntry("state","CANCELLED");
    }
    @Test void lostKeyFailsClosedPurgesBodyAndOldKeysSupportRotation(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);String next=Base64.getEncoder().encodeToString(new byte[32]);
        var rotated=new EmailOutbox(jdbc,manager,new OutboxCrypto(true,"b","a="+key+",b="+next),clock);var lease=rotated.claim(UUID.randomUUID()).orElseThrow();assertThat(rotated.prepare(lease).token()).isEqualTo(c.token());
        var missing=new EmailOutbox(jdbc,manager,new OutboxCrypto(true,"b","b="+next),clock);assertThat(missing.prepare(lease)).isNull();assertThat(row(c.id())).containsEntry("state","FAILED").containsEntry("failure_code","KEY_UNAVAILABLE").containsEntry("ciphertext",null);
    }
    @Test void competingClaimsReturnOneLease() throws Exception {
        UUID id=user(false);issue(id,Purpose.VERIFICATION);CountDownLatch together=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Optional<EmailOutbox.Lease>> action=()->{together.await();return outbox.claim(UUID.randomUUID());};var a=pool.submit(action);var b=pool.submit(action);together.countDown();assertThat(a.get(10,TimeUnit.SECONDS).isPresent()).isNotEqualTo(b.get(10,TimeUnit.SECONDS).isPresent());
        }
    }
    @Test void expiryAndRetentionPurgeBodiesWithoutDeletingAccount(){
        UUID id=user(false);var c=issue(id,Purpose.PASSWORD_RESET);clock.value.set(c.expires());assertThat(outbox.purgeExpired()).isEqualTo(1);assertThat(row(c.id())).containsEntry("state","EXPIRED").containsEntry("ciphertext",null);
        clock.value.set(clock.instant().plus(Duration.ofDays(31)));outbox.purgeExpired();assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE subject_id=?",Long.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void retryExhaustionPurgesAndExpiredLeaseBacksOffWithFencedPayload(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);
        for(int attempt=1;attempt<=6;attempt++){
            var lease=outbox.claim(UUID.randomUUID()).orElseThrow();assertThat(outbox.failed(lease,EmailOutbox.Failure.TRANSIENT)).isTrue();var saved=row(c.id());
            assertThat(saved.get("attempts")).isEqualTo(attempt);
            if(attempt<6){assertThat(saved.get("state")).isEqualTo("RETRY_WAIT");clock.value.set(((java.sql.Timestamp)saved.get("next_attempt_at")).toInstant());}
            else assertThat(saved).containsEntry("state","FAILED").containsEntry("ciphertext",null);
        }
        UUID other=user(false);var abandoned=issue(other,Purpose.VERIFICATION);var old=outbox.claim(UUID.randomUUID()).orElseThrow();clock.value.set(clock.instant().plusSeconds(60));
        assertThat(outbox.accepted(old,UUID.randomUUID())).isFalse();assertThat(outbox.claim(UUID.randomUUID())).isEmpty();assertThat(row(abandoned.id())).containsEntry("state","RETRY_WAIT").containsEntry("failure_code","LEASE_EXPIRED");
        assertThat(row(abandoned.id()).get("ciphertext")).isNotNull();
    }
    @Test void intentRequiresTransactionAndDatabaseRejectsBindingMutation(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);
        assertThatThrownBy(()->outbox.challenge(c)).hasMessage("MAIL_INTENT_TRANSACTION_REQUIRED");
        assertThatThrownBy(()->jdbc.update("UPDATE email_outbox SET event_id=? WHERE event_id=?",UUID.randomUUID(),c.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE email_outbox SET ciphertext=NULL WHERE event_id=?",c.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void clockBeforeCreationCannotPrepareOrFinalizeLease(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);var lease=outbox.claim(UUID.randomUUID()).orElseThrow();Instant created=clock.instant();clock.value.set(created.minusSeconds(1));
        assertThat(outbox.prepare(lease)).isNull();assertThat(outbox.accepted(lease,UUID.randomUUID())).isFalse();assertThat(row(c.id()).get("state")).isEqualTo("SENDING");
        clock.value.set(created);assertThat(outbox.prepare(lease).token()).isEqualTo(c.token());assertThat(outbox.accepted(lease,UUID.randomUUID())).isTrue();
    }
    @Test void restoreReplayCancelsRecoveredMailBeforeWorkerCanClaim(){
        UUID id=user(false);var c=issue(id,Purpose.VERIFICATION);UUID instance=jdbc.queryForObject("SELECT source_instance_id FROM transfer_instance WHERE id=1",UUID.class);
        assertThat(jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'ACCOUNT',clock_timestamp(),clock_timestamp()+interval '30 days')",Boolean.class,UUID.randomUUID(),instance,id)).isTrue();
        assertThat(row(c.id())).containsEntry("state","CANCELLED").containsEntry("ciphertext",null).containsEntry("failure_code","RESTORE_CANCELLED");
        assertThatThrownBy(()->outbox.claim(UUID.randomUUID())).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
    }
}
