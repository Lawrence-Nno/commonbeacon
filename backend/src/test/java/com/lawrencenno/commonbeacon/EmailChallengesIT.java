package com.lawrencenno.commonbeacon;

import tools.jackson.databind.ObjectMapper;
import com.lawrencenno.commonbeacon.identity.EmailChallenges;
import com.lawrencenno.commonbeacon.identity.EmailChallenges.*;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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
class EmailChallengesIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    private final Instant start=Instant.parse("2030-01-01T00:00:00Z");
    private MutableClock clock; private EmailChallenges challenges;
    private final List<UUID> subjects=new ArrayList<>();
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>();
        MutableClock(Instant instant){value.set(instant);}
        @Override public Instant instant(){return value.get();}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
    }
    @BeforeEach void setup(){clock=new MutableClock(start);challenges=new EmailChallenges(jdbc,manager,clock,new SecureRandom(),passwords);}
    @AfterEach void cleanup(){for(UUID id:subjects)jdbc.update("DELETE FROM app_user WHERE id=?",id);subjects.clear();}
    UUID user(String state,boolean verified){
        UUID id=UUID.randomUUID();subjects.add(id);
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,account_state,email_verified_at) VALUES (?,?,?,?,?,?)",
            id,id+"@example.test","Challenge member",passwords.encode("old-password-for-test"),state,verified?java.sql.Timestamp.from(start.minusSeconds(10)):null);
        return id;
    }
    String email(UUID id){return id+"@example.test";}
    Issued issue(UUID id,Purpose purpose){return issue(id,purpose,purpose==Purpose.EMAIL_CHANGE?"new-"+email(id):email(id));}
    Issued issue(UUID id,Purpose purpose,String address){
        var result=new AtomicReference<Issued>();assertThat(challenges.issue(id,purpose,address,()->true,result::set)).isTrue();return result.get();
    }
    void consume(Issued c){challenges.consume(c.purpose(),c.token(),"new-owner-password",done->{});}
    void invalid(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiFailure.class,e->{assertThat(e.status()).isEqualTo(400);assertThat(e.code()).isEqualTo("INVALID_EMAIL_LINK");assertThat(e.getMessage()).isEqualTo("This email link is invalid or has expired.");});}
    Map<String,Object> state(UUID id){return jdbc.queryForMap("SELECT account_state,email,email_verified_at,password_hash,auth_epoch,auth_revision,verification_generation,password_reset_generation,email_change_generation FROM app_user WHERE id=?",id);}

    @Test void pendingVerificationReplacesPasswordRevokesResetsAndRequiresFreshEpoch(){
        UUID id=user("PENDING_VERIFICATION",false);var reset=issue(id,Purpose.PASSWORD_RESET);var verify=issue(id,Purpose.VERIFICATION);
        AtomicReference<Completion> completed=new AtomicReference<>();
        challenges.consume(Purpose.VERIFICATION,verify.token(),"inbox-owner-password",completed::set);
        var a=state(id);assertThat(a).containsEntry("account_state","ACTIVE").containsEntry("auth_epoch",1L).containsEntry("auth_revision",1L);
        assertThat(a.get("email_verified_at")).isNotNull();assertThat(passwords.matches("inbox-owner-password",(String)a.get("password_hash"))).isTrue();
        assertThat(passwords.matches("old-password-for-test",(String)a.get("password_hash"))).isFalse();
        assertThat(completed.get().subject()).isEqualTo(id);invalid(()->consume(verify));invalid(()->consume(reset));
    }
    @Test void legacyVerificationPreservesCredentialsAndRole(){
        UUID id=user("ACTIVE",false);jdbc.update("UPDATE app_user SET role='MODERATOR' WHERE id=?",id);var before=state(id);var c=issue(id,Purpose.VERIFICATION);
        challenges.consume(c.purpose(),c.token(),null,done->{});
        assertThat(state(id).get("password_hash")).isEqualTo(before.get("password_hash"));
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id=?",String.class,id)).isEqualTo("MODERATOR");
        assertThat(state(id).get("auth_epoch")).isEqualTo((Long)before.get("auth_epoch")+1);
    }
    @Test void resetPreservesPendingStateAndVerificationLink(){
        UUID id=user("PENDING_VERIFICATION",false);var verify=issue(id,Purpose.VERIFICATION);var reset=issue(id,Purpose.PASSWORD_RESET);
        consume(reset);assertThat(state(id)).containsEntry("account_state","PENDING_VERIFICATION").containsEntry("email_verified_at",null).containsEntry("auth_epoch",1L);
        invalid(()->consume(reset));consume(verify);assertThat(state(id)).containsEntry("account_state","ACTIVE").containsEntry("auth_epoch",2L);
    }
    @Test void resetPreservesActiveProofAndCancelsEmailProposal(){
        UUID id=user("ACTIVE",true);var change=issue(id,Purpose.EMAIL_CHANGE);var before=state(id);var reset=issue(id,Purpose.PASSWORD_RESET);
        consume(reset);assertThat(state(id).get("email_verified_at")).isEqualTo(before.get("email_verified_at"));invalid(()->consume(change));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_email_change WHERE subject_id=?",Long.class,id)).isZero();
    }
    @Test void emailChangeKeepsOldAddressUntilAtomicCompletionAndRevokesOldLinks(){
        UUID id=user("ACTIVE",true);var reset=issue(id,Purpose.PASSWORD_RESET);var change=issue(id,Purpose.EMAIL_CHANGE);
        assertThat(state(id).get("email")).isEqualTo(email(id));assertThat(state(id).get("auth_epoch")).isEqualTo(0L);
        AtomicReference<Completion> result=new AtomicReference<>();challenges.consume(change.purpose(),change.token(),null,result::set);
        assertThat(state(id)).containsEntry("email","new-"+email(id)).containsEntry("auth_epoch",1L);assertThat(state(id).get("email_verified_at")).isNotNull();
        assertThat(result.get().previousEmail()).isEqualTo(email(id));assertThat(result.get().email()).isEqualTo("new-"+email(id));invalid(()->consume(reset));invalid(()->consume(change));
    }
    @Test void deniedResendLeavesTokenGenerationAndProposalUnchanged(){
        UUID id=user("ACTIVE",true);var c=issue(id,Purpose.EMAIL_CHANGE);var before=state(id);
        assertThat(challenges.issue(id,Purpose.EMAIL_CHANGE,"different-"+email(id),()->true,x->{throw new AssertionError();})).isFalse();
        clock.value.set(start.plusSeconds(60));
        assertThat(challenges.issue(id,Purpose.EMAIL_CHANGE,"different-"+email(id),()->false,x->{throw new AssertionError();})).isFalse();
        assertThat(state(id)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT intended_email FROM pending_email_change WHERE subject_id=?",String.class,id)).isEqualTo(c.email());consume(c);
    }
    @Test void admittedResendAtCooldownBoundaryRotatesOnlyItsPurpose(){
        UUID id=user("ACTIVE",false);var reset=issue(id,Purpose.PASSWORD_RESET);var old=issue(id,Purpose.VERIFICATION);
        clock.value.set(start.plusSeconds(60));var latest=issue(id,Purpose.VERIFICATION);
        assertThat(latest.generation()).isEqualTo(2);assertThat(latest.token()).isNotEqualTo(old.token());
        assertThat(state(id)).containsEntry("auth_epoch",0L).containsEntry("password_reset_generation",1L);
        invalid(()->consume(old));consume(reset);consume(latest);
    }
    @Test void exactExpiryAndClockBeforeCreationFailWithSameOutcome(){
        for(Purpose purpose:Purpose.values()){
            UUID id=user("ACTIVE",purpose==Purpose.EMAIL_CHANGE);clock.value.set(start);var c=issue(id,purpose);
            assertThat(Duration.between(start,c.expires())).isEqualTo(purpose==Purpose.VERIFICATION?Duration.ofHours(24):Duration.ofMinutes(30));
            clock.value.set(start.minusNanos(1));invalid(()->consume(c));clock.value.set(c.expires());invalid(()->consume(c));
            clock.value.set(c.expires().minusNanos(1000));consume(c);
        }
    }
    @Test void issuedExpiryMatchesPersistedMicrosecondPrecision(){
        UUID id=user("ACTIVE",false);clock.value.set(start.plusNanos(123456789));var c=issue(id,Purpose.VERIFICATION);
        var persisted=jdbc.queryForObject("SELECT expires_at FROM email_challenge WHERE id=?",java.sql.Timestamp.class,c.id()).toInstant();
        assertThat(c.expires()).isEqualTo(persisted).isEqualTo(start.plusNanos(123456000).plus(Duration.ofHours(24)));
        clock.value.set(c.expires());invalid(()->consume(c));
    }
    @Test void malformedAndWrongPurposeTokensAreBoundedAndGeneric(){
        UUID id=user("ACTIVE",false);var c=issue(id,Purpose.VERIFICATION);
        for(String token:new String[]{null,"",c.token()+"=","a".repeat(100000),"/".repeat(43),c.token().substring(0,42)+"!"})invalid(()->challenges.consume(Purpose.VERIFICATION,token,null,x->{}));
        invalid(()->challenges.consume(Purpose.PASSWORD_RESET,c.token(),"new-owner-password",x->{}));
        invalid(()->challenges.consume(Purpose.EMAIL_CHANGE,c.token(),null,x->{}));consume(c);
    }
    @Test void digestsArePurposeSeparatedAndRepresentationsCannotLeakSecrets() throws Exception {
        UUID id=user("ACTIVE",false);var c=issue(id,Purpose.VERIFICATION);String stored=jdbc.queryForObject("SELECT token_digest FROM email_challenge WHERE id=?",String.class,c.id());
        assertThat(c.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");assertThat(stored).matches("[0-9a-f]{64}").doesNotContain(c.token());
        assertThat(c.toString()).isEqualTo("Issued[REDACTED]");
        assertThat(mapper.writeValueAsString(c)).isEqualTo("{}");
        AtomicReference<Completion> done=new AtomicReference<>();challenges.consume(c.purpose(),c.token(),null,done::set);
        assertThat(done.get().toString()).isEqualTo("Completion[REDACTED]");
        assertThat(mapper.writeValueAsString(done.get())).isEqualTo("{}");
    }
    @Test void failedIssueCallbackRollsBackResendAndOriginalRemainsUsable(){
        UUID id=user("ACTIVE",false);var original=issue(id,Purpose.VERIFICATION);clock.value.set(start.plusSeconds(60));var before=state(id);
        assertThatThrownBy(()->challenges.issue(id,Purpose.VERIFICATION,email(id),()->true,c->{throw new IllegalStateException("TEST_ROLLBACK");})).hasMessage("TEST_ROLLBACK");
        assertThat(state(id)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=?",Long.class,id)).isEqualTo(1);consume(original);
    }
    @Test void failedCompletionAndOuterTransactionRollBackAccountAndToken(){
        UUID id=user("PENDING_VERIFICATION",false);var c=issue(id,Purpose.VERIFICATION);var before=state(id);
        assertThatThrownBy(()->challenges.consume(c.purpose(),c.token(),"new-owner-password",done->{throw new IllegalStateException("TEST_ROLLBACK");})).hasMessage("TEST_ROLLBACK");
        assertThat(state(id)).isEqualTo(before);
        new TransactionTemplate(manager).executeWithoutResult(tx->{consume(c);tx.setRollbackOnly();});assertThat(state(id)).isEqualTo(before);consume(c);
    }
    @Test void passwordPolicyDoesNotConsumePendingVerification(){
        UUID id=user("PENDING_VERIFICATION",false);var c=issue(id,Purpose.VERIFICATION);
        for(String password:new String[]{null,"short"," ".repeat(12),"a".repeat(129)})assertThatThrownBy(()->challenges.consume(c.purpose(),c.token(),password,x->{})).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo("INVALID_PASSWORD"));
        assertThat(state(id)).containsEntry("account_state","PENDING_VERIFICATION").containsEntry("auth_epoch",0L);consume(c);
    }
    @Test void addressGenerationProposalAndSubjectChangesInvalidateBindings(){
        UUID wrongAddress=user("ACTIVE",false);var a=issue(wrongAddress,Purpose.VERIFICATION);jdbc.update("UPDATE app_user SET email=? WHERE id=?","other-"+email(wrongAddress),wrongAddress);invalid(()->consume(a));
        UUID wrongGeneration=user("ACTIVE",false);var g=issue(wrongGeneration,Purpose.PASSWORD_RESET);jdbc.update("UPDATE app_user SET password_reset_generation=password_reset_generation+1 WHERE id=?",wrongGeneration);invalid(()->consume(g));
        UUID proposal=user("ACTIVE",true);var p=issue(proposal,Purpose.EMAIL_CHANGE);jdbc.update("DELETE FROM pending_email_change WHERE subject_id=?",proposal);invalid(()->consume(p));
        for(String state:new String[]{"SUSPENDED","ERASED"}){
            UUID id=user("ACTIVE",false);var c=issue(id,Purpose.VERIFICATION);
            if(state.equals("ERASED"))jdbc.update("UPDATE app_user SET account_state='ERASED',email=NULL,password_hash=NULL,display_name='Deleted member' WHERE id=?",id);
            else jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",id);
            invalid(()->consume(c));assertThat(challenges.issue(id,Purpose.VERIFICATION,email(id),()->true,x->{throw new AssertionError();})).isFalse();
        }
        UUID deleted=user("ACTIVE",false);var d=issue(deleted,Purpose.VERIFICATION);jdbc.update("DELETE FROM app_user WHERE id=?",deleted);invalid(()->consume(d));
    }
    @Test void collidingEmailCompletionRollsBackAndRetainsOriginalToken(){
        UUID id=user("ACTIVE",true),other=user("ACTIVE",true);var c=issue(id,Purpose.EMAIL_CHANGE,email(other));var before=state(id);
        invalid(()->consume(c));assertThat(state(id)).isEqualTo(before);jdbc.update("DELETE FROM app_user WHERE id=?",other);consume(c);
    }
    @Test void newAddressProofSurvivesEqualClockTimestamp(){
        UUID id=user("ACTIVE",true);jdbc.update("UPDATE app_user SET email_verified_at=? WHERE id=?",java.sql.Timestamp.from(start),id);
        var c=issue(id,Purpose.EMAIL_CHANGE);consume(c);
        assertThat(state(id).get("email_verified_at")).isNotNull();assertThat(state(id).get("email")).isEqualTo(c.email());
    }
    @Test void sameRandomBytesProduceDifferentPurposeDigests(){
        UUID id=user("ACTIVE",false);
        challenges=new EmailChallenges(jdbc,manager,clock,new SecureRandom(){@Override public void nextBytes(byte[] bytes){Arrays.fill(bytes,(byte)7);}},passwords);
        var verification=issue(id,Purpose.VERIFICATION);var reset=issue(id,Purpose.PASSWORD_RESET);
        assertThat(verification.token()).isEqualTo(reset.token());
        assertThat(jdbc.queryForList("SELECT token_digest FROM email_challenge WHERE subject_id=?",String.class,id)).hasSize(2).doesNotHaveDuplicates();
    }
    @Test void competingConsumersPerformExactlyOneTransition() throws Exception {
        for(Purpose purpose:Purpose.values()){
        UUID id=user(purpose==Purpose.VERIFICATION?"PENDING_VERIFICATION":"ACTIVE",purpose==Purpose.EMAIL_CHANGE);var c=issue(id,purpose);AtomicInteger successes=new AtomicInteger(),invalids=new AtomicInteger();CountDownLatch startTogether=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{startTogether.await();consume(c);successes.incrementAndGet();}catch(ApiFailure failure){assertThat(failure.code()).isEqualTo("INVALID_EMAIL_LINK");invalids.incrementAndGet();}catch(InterruptedException failure){throw new RuntimeException(failure);}}));
            startTogether.countDown();for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isEqualTo(1);assertThat(invalids.get()).isEqualTo(1);assertThat(state(id)).containsEntry("auth_epoch",1L);
        }
    }
    @Test void concurrentIssuersCreateOneGenerationAndOneIntent() throws Exception {
        UUID id=user("ACTIVE",false);CountDownLatch together=new CountDownLatch(1);AtomicInteger intents=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Boolean> issue=()->{together.await();return challenges.issue(id,Purpose.VERIFICATION,email(id),()->true,c->intents.incrementAndGet());};
            var first=pool.submit(issue);var second=pool.submit(issue);together.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isNotEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(intents.get()).isEqualTo(1);assertThat(state(id)).containsEntry("verification_generation",1L).containsEntry("auth_epoch",0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void suspensionSerializesAfterInFlightConsumption() throws Exception {
        UUID id=user("ACTIVE",false);var c=issue(id,Purpose.VERIFICATION);CountDownLatch transitioned=new CountDownLatch(1),release=new CountDownLatch(1),changing=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var consumer=pool.submit(()->challenges.consume(c.purpose(),c.token(),null,done->{transitioned.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException error){throw new RuntimeException(error);}}));
            assertThat(transitioned.await(5,TimeUnit.SECONDS)).isTrue();
            var suspension=pool.submit(()->{changing.countDown();jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",id);});
            assertThat(changing.await(5,TimeUnit.SECONDS)).isTrue();
            try{assertThatThrownBy(()->suspension.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            consumer.get(10,TimeUnit.SECONDS);suspension.get(10,TimeUnit.SECONDS);
        }
        assertThat(state(id)).containsEntry("account_state","SUSPENDED").containsEntry("auth_epoch",2L);invalid(()->consume(c));
    }
    @Test void expiredProposalAndWrongIssuanceAddressGrantNothing(){
        UUID id=user("ACTIVE",true);var c=issue(id,Purpose.EMAIL_CHANGE);
        jdbc.update("UPDATE pending_email_change SET expires_at=? WHERE subject_id=?",java.sql.Timestamp.from(start.plusSeconds(1)),id);clock.value.set(start.plusSeconds(2));invalid(()->consume(c));
        UUID other=user("ACTIVE",false);
        assertThat(challenges.issue(other,Purpose.VERIFICATION,email(id),()->true,x->{throw new AssertionError();})).isFalse();
        assertThat(state(other)).containsEntry("verification_generation",0L).containsEntry("email_verified_at",null);
    }
    @Test void resendAndConsumeRaceHasOnlyValidSerializedOutcomes() throws Exception {
        UUID id=user("ACTIVE",false);var old=issue(id,Purpose.VERIFICATION);clock.value.set(start.plusSeconds(60));CountDownLatch together=new CountDownLatch(1);AtomicReference<Issued> latest=new AtomicReference<>();AtomicBoolean consumed=new AtomicBoolean();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var resend=pool.submit(()->{together.await();return challenges.issue(id,Purpose.VERIFICATION,email(id),()->true,latest::set);});
            var verify=pool.submit(()->{together.await();try{consume(old);consumed.set(true);}catch(ApiFailure error){assertThat(error.code()).isEqualTo("INVALID_EMAIL_LINK");}return null;});
            together.countDown();boolean resent=resend.get(10,TimeUnit.SECONDS);verify.get(10,TimeUnit.SECONDS);
            assertThat(resent).isNotEqualTo(consumed.get());if(resent){assertThat(state(id).get("email_verified_at")).isNull();consume(latest.get());}
        }
        assertThat(state(id).get("email_verified_at")).isNotNull();assertThat(state(id)).containsEntry("auth_epoch",1L);invalid(()->consume(old));
    }
}
