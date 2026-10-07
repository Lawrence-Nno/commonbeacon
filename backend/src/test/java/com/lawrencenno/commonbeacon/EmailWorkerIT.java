package com.lawrencenno.commonbeacon;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.lawrencenno.commonbeacon.identity.*;
import com.lawrencenno.commonbeacon.identity.EmailChallenges.*;
import com.lawrencenno.commonbeacon.identity.mail.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"commonbeacon.erasure.worker-enabled=false"})
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EmailWorkerIT {
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;@Autowired PasswordEncoder passwords;
    final List<UUID> users=new ArrayList<>();final List<EmailWorker> workers=new ArrayList<>();
    EmailChallengesIT.MutableClock clock;OutboxCrypto crypto;EmailChallenges challenges;EmailOutbox outbox;EmailIdentity identity;MailSettings settings;
    @BeforeEach void setup(){
        clock=new EmailChallengesIT.MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        crypto=new OutboxCrypto(true,"fixture","fixture="+Base64.getEncoder().encodeToString(new byte[32]));
        challenges=new EmailChallenges(jdbc,manager,clock,new SecureRandom(),passwords);outbox=new EmailOutbox(jdbc,manager,crypto,clock);identity=new EmailIdentity(challenges,outbox,crypto);
        var env=new MockEnvironment();env.setActiveProfiles("local");
        env.withProperty("commonbeacon.email.smtp.enabled","true").withProperty("commonbeacon.email.smtp.host","127.0.0.1")
          .withProperty("commonbeacon.email.smtp.port","1025").withProperty("commonbeacon.email.smtp.tls","NONE")
          .withProperty("commonbeacon.email.smtp.sender","accounts@example.test").withProperty("commonbeacon.email.smtp.support","support@example.test")
          .withProperty("commonbeacon.email.smtp.public-origin","http://127.0.0.1:4173");settings=new MailSettings(env,crypto);
    }
    @AfterEach void cleanup(){
        for(var worker:workers){worker.stop();await().atMost(Duration.ofSeconds(5)).until(()->worker.inFlight()==0);}
        // Clear the simulated erasure barrier before resetting this disposable context.
        jdbc.execute("TRUNCATE erasure_job,erasure_tombstone,email_transport_budget CASCADE");
        jdbc.execute("TRUNCATE app_user CASCADE");
    }
    UUID user(){UUID id=UUID.randomUUID();users.add(id);jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash) VALUES (?,?,'Worker test',?)",id,id+"@example.test",passwords.encode("worker-old-password"));return id;}
    Issued issue(UUID user,Purpose purpose){var result=new AtomicReference<Issued>();assertThat(challenges.issue(user,purpose,user+"@example.test",()->true,c->{outbox.challenge(c);result.set(c);})).isTrue();return result.get();}
    UUID id(Issued c){return jdbc.queryForObject("SELECT id FROM email_outbox WHERE event_id=? AND message_type=?",UUID.class,c.id(),c.purpose().name());}
    Map<String,Object> row(UUID id){return jdbc.queryForMap("SELECT * FROM email_outbox WHERE id=?",id);}
    void state(UUID id,String state){await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(row(id).get("state")).isEqualTo(state));}
    EmailWorker worker(EmailOutbox box,MailTransport transport){return worker(box,transport,1000,200);}
    EmailWorker worker(EmailOutbox box,MailTransport transport,int timeout,int shutdown){
        var env=new MockEnvironment().withProperty("commonbeacon.email.worker.enabled","true").withProperty("commonbeacon.email.worker.interval-ms","60000")
          .withProperty("commonbeacon.email.worker.timeout-ms",""+timeout).withProperty("commonbeacon.email.worker.shutdown-ms",""+shutdown);
        var worker=new EmailWorker(box,new MailTemplates(settings),transport,new MailWorkerSettings(env,settings));workers.add(worker);worker.start();return worker;
    }
    @Test void acceptedMailPurgesBodyWithoutVerifyingOrConsumingChallenge(){
        UUID user=user();var c=issue(user,Purpose.VERIFICATION);UUID id=id(c);var peer=new Peer(false,null);var worker=worker(outbox,peer);
        assertThat(worker.runOnce()).isEqualTo(1);state(id,"ACCEPTED");assertThat(row(id)).containsEntry("ciphertext",null).containsEntry("nonce",null);
        assertThat(peer.bodies.getFirst().text()).contains(c.token());assertThat(peer.transactionSeen.get()).isFalse();
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM app_user WHERE id=?",Timestamp.class,user)).isNull();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM email_challenge WHERE id=?",Timestamp.class,c.id())).isNull();
    }
    @Test void providerOutageDoesNotBlockIdentityWriterAndLateReceiptCannotResurrectCancelledMail() throws Exception {
        UUID user=user();var c=issue(user,Purpose.VERIFICATION);UUID id=id(c);var peer=new Peer(true,null);var worker=worker(outbox,peer,2000,200);
        worker.runOnce();assertThat(peer.entered.await(3,TimeUnit.SECONDS)).isTrue();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            executor.submit(()->identity.consume(c.purpose(),c.token(),null)).get(1,TimeUnit.SECONDS);
        }
        state(id,"CANCELLED");peer.release.countDown();await().atMost(Duration.ofSeconds(3)).until(()->worker.inFlight()==0);
        assertThat(row(id)).containsEntry("state","CANCELLED").containsEntry("ciphertext",null);
    }
    @Test void competingWorkersNeverHaveMoreThanTwoDurableSends() throws Exception {
        for(int i=0;i<4;i++)issue(user(),Purpose.VERIFICATION);
        var peer=new Peer(true,null);var first=worker(outbox,peer,2000,200);var second=worker(new EmailOutbox(jdbc,manager,crypto,clock),peer,2000,200);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(first::runOnce);var b=executor.submit(second::runOnce);assertThat(a.get()+b.get()).isEqualTo(2);
        }
        await().atMost(Duration.ofSeconds(3)).until(()->peer.calls.get()==2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE state='SENDING'",Long.class)).isEqualTo(2);
        peer.release.countDown();await().atMost(Duration.ofSeconds(3)).until(()->first.inFlight()+second.inFlight()==0);
        first.runOnce();second.runOnce();await().atMost(Duration.ofSeconds(3)).until(()->peer.calls.get()==4);
        assertThat(peer.max.get()).isLessThanOrEqualTo(2);
    }
    @Test void crashAfterRemoteAcceptanceRecoversSameMessageAndAccountTransitionRemainsOneUse(CapturedOutput output){
        var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);var before=row(id);var peer=new Peer(false,null);
        var broken=new EmailOutbox(jdbc,manager,crypto,clock){@Override public boolean accepted(Lease lease,UUID correlation){throw new IllegalStateException("private-provider-"+c.token());}};
        var first=worker(broken,peer);first.runOnce();await().atMost(Duration.ofSeconds(3)).until(()->peer.calls.get()==1 && first.inFlight()==0);
        assertThat(row(id).get("state")).isEqualTo("SENDING");first.stop();
        clock.value.set(((Timestamp)row(id).get("lease_until")).toInstant());var restarted=worker(new EmailOutbox(jdbc,manager,crypto,clock),peer);
        assertThat(restarted.runOnce()).isZero();assertThat(row(id)).containsEntry("state","RETRY_WAIT").containsEntry("failure_code","LEASE_EXPIRED");
        assertThat(row(id).get("ciphertext")).isEqualTo(before.get("ciphertext"));assertThat(row(id).get("expires_at")).isEqualTo(before.get("expires_at"));
        clock.value.set(((Timestamp)row(id).get("next_attempt_at")).toInstant());restarted.runOnce();state(id,"ACCEPTED");
        assertThat(peer.ids).containsExactly(id,id);assertThat(peer.bodies.get(0).text()).isEqualTo(peer.bodies.get(1).text());
        identity.consume(c.purpose(),c.token(),null);assertThatThrownBy(()->identity.consume(c.purpose(),c.token(),null)).isInstanceOf(com.lawrencenno.commonbeacon.shared.ApiFailure.class);
        assertThat(output.getAll()).doesNotContain(c.token(),"private-provider-");
    }
    @Test void deadlineAndShutdownPreserveImmutableRetryPayloadAndReleaseSlots() throws Exception {
        var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);var original=row(id);var peer=new Peer(true,null);var worker=worker(outbox,peer,100,100);
        worker.runOnce();state(id,"RETRY_WAIT");assertThat(row(id)).containsEntry("failure_code","TIMEOUT");assertThat(row(id).get("ciphertext")).isEqualTo(original.get("ciphertext"));
        clock.value.set(((Timestamp)row(id).get("next_attempt_at")).toInstant());var hold=new Peer(true,null);var stopping=worker(outbox,hold,2000,100);stopping.runOnce();assertThat(hold.entered.await(3,TimeUnit.SECONDS)).isTrue();
        stopping.stop();await().atMost(Duration.ofSeconds(3)).until(()->stopping.inFlight()==0);state(id,"RETRY_WAIT");assertThat(stopping.runOnce()).isZero();
    }
    @Test void safeTerminalCategoriesPurgeAndCannotBeOperatorRetried(){
        for(var kind:List.of(MailTransport.Failure.PERMANENT,MailTransport.Failure.CONFIGURATION,MailTransport.Failure.SUPPRESSED,MailTransport.Failure.INVALID_MESSAGE)){
            var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);var worker=worker(outbox,new Peer(false,kind));worker.runOnce();state(id,"FAILED");
            assertThat(row(id)).containsEntry("ciphertext",null).containsEntry("failure_code",kind==MailTransport.Failure.INVALID_MESSAGE?"PAYLOAD_INVALID":kind.name());
            assertThat(operate(id,((Number)row(id).get("lease_version")).longValue(),"retry")).isFalse();worker.stop();
        }
    }
    @Test void transportBudgetsAreDurableSeparateAndReserveSecurityCapacity(){
        var c=issue(user(),Purpose.VERIFICATION);var lease=outbox.claim(UUID.randomUUID(),10,false).orElseThrow();outbox.failed(lease,EmailOutbox.Failure.TRANSIENT);
        clock.value.set(((Timestamp)row(id(c)).get("next_attempt_at")).toInstant());
        jdbc.update("UPDATE email_transport_budget SET ordinary=8,retries=3 WHERE window_start=?",Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.HOURS)));
        assertThat(new EmailOutbox(jdbc,manager,crypto,clock).claim(UUID.randomUUID(),10,false)).isEmpty();
        var alertSource=issue(user(),Purpose.PASSWORD_RESET);identity.consume(alertSource.purpose(),alertSource.token(),"worker-new-password");
        var alert=outbox.claim(UUID.randomUUID(),10,true).orElseThrow();assertThat(outbox.prepareDelivery(alert).type()).isEqualTo(EmailOutbox.Type.PASSWORD_CHANGED);
        outbox.accepted(alert,alert.id());
        jdbc.update("UPDATE email_transport_budget SET ordinary=1,alerts=0,retries=1");
        // A full retry allocation does not block fresh ordinary mail below its own cap.
        jdbc.update("UPDATE email_transport_budget SET retries=3,ordinary=3");var fresh=issue(user(),Purpose.VERIFICATION);
        var next=outbox.claim(UUID.randomUUID(),10,false).orElseThrow();assertThat(next.id()).isEqualTo(id(fresh));
    }
    @Test void parallelClaimsCannotSpendBeyondLastTransportBudgetSlot() throws Exception {
        for(int i=0;i<3;i++)issue(user(),Purpose.VERIFICATION);
        jdbc.update("INSERT INTO email_transport_budget(window_start,ordinary) VALUES (?,7)",Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.HOURS)));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->outbox.claim(UUID.randomUUID(),10,false));var b=executor.submit(()->outbox.claim(UUID.randomUUID(),10,false));assertThat(a.get().isPresent()).isNotEqualTo(b.get().isPresent());
        }
        assertThat(jdbc.queryForObject("SELECT ordinary FROM email_transport_budget",Integer.class)).isEqualTo(8);
    }
    @Test void expiredHeadDoesNotBlockFreshMailOrSpendTransportBudget(){
        var expired=issue(user(),Purpose.VERIFICATION);UUID oldId=id(expired);
        clock.value.set(expired.expires());var fresh=issue(user(),Purpose.VERIFICATION);
        var lease=outbox.claim(UUID.randomUUID()).orElseThrow();assertThat(lease.id()).isEqualTo(id(fresh));
        assertThat(row(oldId)).containsEntry("state","EXPIRED").containsEntry("ciphertext",null).containsEntry("attempts",0);
        assertThat(jdbc.queryForObject("SELECT sum(ordinary) FROM email_transport_budget",Long.class)).isEqualTo(1);
    }
    boolean operate(UUID id,long version,String action){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT operate_email_outbox(?,?,?)",Boolean.class,id,version,action));}
    @Test void operatorControlsFenceStaleVersionsAndNeverChangeAttemptsExpiryOrPayload(){
        var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);var lease=outbox.claim(UUID.randomUUID()).orElseThrow();outbox.failed(lease,EmailOutbox.Failure.TRANSIENT);var before=row(id);long version=((Number)before.get("lease_version")).longValue();
        assertThat(operate(id,version-1,"retry")).isFalse();assertThat(operate(id,version,"retry")).isTrue();
        assertThat(row(id).get("attempts")).isEqualTo(before.get("attempts"));assertThat(row(id).get("ciphertext")).isEqualTo(before.get("ciphertext"));assertThat(row(id).get("expires_at")).isEqualTo(before.get("expires_at"));
        assertThat(operate(id,version,"cancel")).isFalse();assertThat(operate(id,version+1,"cancel")).isTrue();assertThat(row(id)).containsEntry("state","CANCELLED").containsEntry("ciphertext",null);
        assertThat(operate(id,version+2,"retry")).isFalse();assertThat(operate(UUID.randomUUID(),0,"cancel")).isFalse();
    }
    @Test void cleanupFailureDoesNotBlockDeliveryAndRestartRetriesCleanup(){
        var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);
        var cleanupFails=new EmailOutbox(jdbc,manager,crypto,clock){@Override public int purgeExpired(){throw new IllegalStateException("private-cleanup-error");}};
        var worker=worker(cleanupFails,new Peer(false,null));worker.runOnce();state(id,"ACCEPTED");worker.stop();
        clock.value.set(clock.instant().plus(Duration.ofDays(31)));var restarted=worker(new EmailOutbox(jdbc,manager,crypto,clock),new Peer(false,null));restarted.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE id=?",Long.class,id)).isZero();
    }
    @Test void companyErasureMaintenanceAndLeaseFinalizationDoNotDeadlock() throws Exception {
        var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);var peer=new Peer(true,null);var worker=worker(outbox,peer,2000,200);worker.runOnce();assertThat(peer.entered.await(3,TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE app_user SET role='ADMINISTRATOR' WHERE id=?",c.subject());
        UUID instance=jdbc.queryForObject("SELECT source_instance_id FROM transfer_instance WHERE id=1",UUID.class);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            executor.submit(()->jdbc.queryForObject("SELECT queue_restored_erasure(?,?,?,'COMPANY',clock_timestamp(),clock_timestamp()+interval '30 days')",Boolean.class,UUID.randomUUID(),instance,c.subject())).get(2,TimeUnit.SECONDS);
        }
        peer.release.countDown();await().atMost(Duration.ofSeconds(3)).until(()->worker.inFlight()==0);state(id,"CANCELLED");
        assertThat(worker.runOnce()).isZero();assertThat(row(id).get("ciphertext")).isNull();
    }
    @Test void scheduledWorkerSendsEncryptedOutboxThroughRealSmtp() throws Exception {
        try(var capture=new org.testcontainers.containers.GenericContainer<>("axllent/mailpit:v1.27.4@sha256:df6c2541907e1be6fac21f509927cf6ed771617a1f4b361ef66d97bd05593d2d")
            .withExposedPorts(1025).withEnv("MP_DISABLE_VERSION_CHECK","true")
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(1025))))){
            capture.start();
            var env=new MockEnvironment();env.setActiveProfiles("local");
            env.withProperty("commonbeacon.email.smtp.enabled","true").withProperty("commonbeacon.email.smtp.host",capture.getHost())
              .withProperty("commonbeacon.email.smtp.port",""+capture.getMappedPort(1025)).withProperty("commonbeacon.email.smtp.tls","NONE")
              .withProperty("commonbeacon.email.smtp.sender","accounts@example.test").withProperty("commonbeacon.email.smtp.support","support@example.test")
              .withProperty("commonbeacon.email.smtp.public-origin","http://127.0.0.1:4173")
              .withProperty("commonbeacon.email.worker.enabled","true").withProperty("commonbeacon.email.worker.interval-ms","100");
            var smtpSettings=new MailSettings(env,crypto);
            var c=issue(user(),Purpose.VERIFICATION);UUID id=id(c);
            var worker=new EmailWorker(outbox,new MailTemplates(smtpSettings),new SmtpMailTransport(smtpSettings),new MailWorkerSettings(env,smtpSettings));
            workers.add(worker);worker.start();state(id,"ACCEPTED");
            assertThat(row(id)).containsEntry("ciphertext",null).containsEntry("provider_correlation",id);
            assertThat(jdbc.queryForObject("SELECT consumed_at FROM email_challenge WHERE id=?",Timestamp.class,c.id())).isNull();
        }
    }
    static final class Peer implements MailTransport {
        final AtomicInteger calls=new AtomicInteger(),inFlight=new AtomicInteger(),max=new AtomicInteger();final AtomicBoolean transactionSeen=new AtomicBoolean();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final boolean hold;final Failure failure;
        final List<UUID> ids=new CopyOnWriteArrayList<>();final List<Message> bodies=new CopyOnWriteArrayList<>();
        Peer(boolean hold,Failure failure){this.hold=hold;this.failure=failure;}
        @Override public UUID send(UUID id,Message message){throw new AssertionError("Worker must pass its deadline control");}
        @Override public UUID send(UUID id,Message message,MailAttempt attempt){
            int concurrent=inFlight.incrementAndGet();max.accumulateAndGet(concurrent,Math::max);calls.incrementAndGet();ids.add(id);bodies.add(message);entered.countDown();
            transactionSeen.compareAndSet(false,TransactionSynchronizationManager.isActualTransactionActive());
            try{if(hold){attempt.watch(()->release.countDown());release.await(3,TimeUnit.SECONDS);}attempt.check();if(failure!=null)throw new TransportFailure(failure);return id;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new TransportFailure(Failure.TRANSIENT);}
            finally{inFlight.decrementAndGet();}
        }
    }
}
