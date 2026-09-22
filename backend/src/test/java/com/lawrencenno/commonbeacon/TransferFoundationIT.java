package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import static com.lawrencenno.commonbeacon.transfer.job.TransferJob.*;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false"})
@Import(PostgresTestConfiguration.class)
class TransferFoundationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferJobs jobs;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path directory;
    UUID admin,other,member;
    ArtifactStore store;
    @BeforeEach void setup() throws IOException {
        jdbc.execute("TRUNCATE transfer_audit,transfer_completion,transfer_mapping,transfer_artifact,transfer_attempt,transfer_request,transfer_job");
        admin=user("ADMINISTRATOR");other=user("ADMINISTRATOR");member=user("MEMBER");
        store=new LocalArtifactStore(directory,134217728L,0);
    }
    UUID user(String role) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,'Transfer tester','non-login-test-hash',?)",id,id+"@example.test",role);return id;
    }
    TransferJob create(UUID actor) {return jobs.create(actor,Kind.COMPANY_EXPORT,UUID.randomUUID(),"a".repeat(64));}
    void expire(UUID job) {jdbc.update("UPDATE transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job);}
    long events(UUID job,String event) {return jdbc.queryForObject("SELECT count(*) FROM transfer_audit WHERE job_id=? AND event=?",Long.class,job,event);}
    @Test void creationIsDurableIdempotentAndOwnerScoped() {
        UUID key=UUID.randomUUID();var first=jobs.create(admin,Kind.COMPANY_EXPORT,key,"a".repeat(64));
        var restarted=new TransferJobs(jdbc,transactions);
        assertThat(restarted.create(admin,Kind.COMPANY_EXPORT,key,"a".repeat(64)).id()).isEqualTo(first.id());
        assertThat(events(first.id(),"CREATED")).isEqualTo(1);
        assertThatThrownBy(()->jobs.create(admin,Kind.COMPANY_EXPORT,key,"b".repeat(64))).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->jobs.status(other,first.id())).hasMessage("JOB_NOT_FOUND");
        assertThatThrownBy(()->jobs.create(member,Kind.COMPANY_EXPORT,UUID.randomUUID(),"a".repeat(64))).hasMessage("FORBIDDEN");
        assertThat(jobs.create(member,Kind.PERSONAL_EXPORT,UUID.randomUUID(),"a".repeat(64)).kind()).isEqualTo(Kind.PERSONAL_EXPORT);
    }
    @Test void competingWorkersHaveOneDeploymentWideLease() throws Exception {
        create(admin);create(other);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return jobs.claim(UUID.randomUUID());});
            var b=pool.submit(()->{start.await();return new TransferJobs(jdbc,transactions).claim(UUID.randomUUID());});
            start.countDown();
            assertThat((a.get(15,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(15,TimeUnit.SECONDS).isPresent()?1:0)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_attempt",Integer.class)).isEqualTo(1);
    }
    @Test void leaseRecoveryFencesOldProgressAndRestartsExportSnapshot() {
        var intent=create(admin);var first=jobs.claim(UUID.randomUUID()).orElseThrow();
        assertThat(jobs.checkpoint(first.lease(),42)).isTrue();expire(intent.id());
        var second=new TransferJobs(jdbc,transactions).claim(UUID.randomUUID()).orElseThrow();
        assertThat(second.fence()).isGreaterThan(first.fence());assertThat(second.checkpoint()).isZero();
        assertThatThrownBy(()->jobs.heartbeat(first.lease())).hasMessage("STALE_LEASE");
        assertThatThrownBy(()->jobs.checkpoint(first.lease(),43)).hasMessage("STALE_LEASE");
        assertThat(jobs.heartbeat(second.lease())).isTrue();
        assertThat(jobs.checkpoint(second.lease(),5)).isTrue();
        assertThatThrownBy(()->jobs.checkpoint(second.lease(),4)).hasMessage("STALE_CHECKPOINT");
    }
    @Test void retriesAndJobDeadlineAreBounded() {
        var intent=create(admin);
        for(int i=0;i<3;i++){jobs.claim(UUID.randomUUID()).orElseThrow();expire(intent.id());}
        assertThat(jobs.claim(UUID.randomUUID())).isEmpty();assertThat(jobs.status(admin,intent.id()).state()).isEqualTo(State.FAILED);
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,intent.id())).isEqualTo("ATTEMPTS_EXHAUSTED");
        jobs.releaseCleanedReservations();
        var next=create(admin);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        jdbc.update("UPDATE transfer_job SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",next.id());
        assertThat(jobs.heartbeat(lease)).isFalse();assertThat(jobs.status(admin,next.id()).state()).isEqualTo(State.FAILED);
    }
    @Test void roleRevocationFailsWithoutRetry() {
        var j=create(admin);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        jdbc.update("UPDATE app_user SET role='MEMBER' WHERE id=?",admin);
        assertThat(jobs.heartbeat(lease)).isFalse();
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,j.id())).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(jobs.claim(UUID.randomUUID())).isEmpty();
    }
    @Test void publicationAndLostSuccessAreAtomicAndIdempotent() throws IOException {
        var j=create(admin);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        UUID key=jobs.beginArtifact(lease,"DOWNLOAD",100);var file=store.write(key,100,o->o.write("archive".getBytes()));
        jdbc.update("UPDATE transfer_artifact SET expires_at=clock_timestamp()+interval '1 minute' WHERE id=?",key);
        assertThat(jobs.publish(lease,key,file.bytes(),file.sha256())).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at>clock_timestamp()+interval '23 hours' FROM transfer_artifact WHERE id=?",Boolean.class,key)).isTrue();
        assertThat(new TransferJobs(jdbc,transactions).publish(lease,key,file.bytes(),file.sha256())).isTrue();
        assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.READY);
        assertThat(events(j.id(),"COMPLETED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_completion",Integer.class)).isEqualTo(1);
        assertThat(jobs.publish(new Lease(j.id(),UUID.randomUUID(),lease.fence()),key,file.bytes(),file.sha256())).isFalse();
        assertThatThrownBy(()->jobs.cancel(admin,j.id(),jobs.status(admin,j.id()).version())).hasMessage("JOB_CONFLICT");
    }
    @Test void cancelledWorkerCannotPublishAndCleanupRemovesItsFiles() throws IOException {
        var j=create(admin);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        UUID key=jobs.beginArtifact(lease,"DOWNLOAD",100);var file=store.write(key,100,o->o.write(1));
        var current=jobs.status(admin,j.id());jobs.cancel(admin,j.id(),current.version());
        assertThatThrownBy(()->jobs.publish(lease,key,file.bytes(),file.sha256())).hasMessage("STALE_LEASE");
        new ArtifactReconciler(jobs,store).reconcile();assertThat(store.inspect(key)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM transfer_job WHERE id=?",Long.class,j.id())).isZero();
    }
    @Test void crashAfterRenameLeavesUnpublishedFileForRecovery() throws IOException {
        var j=create(admin);var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();
        UUID key=jobs.beginArtifact(lease,"DOWNLOAD",100);store.write(key,100,o->o.write(1));
        expire(j.id());var replacement=jobs.claim(UUID.randomUUID()).orElseThrow();
        new ArtifactReconciler(jobs,new LocalArtifactStore(directory,134217728L,0)).reconcile();
        assertThat(store.inspect(key)).isEmpty();assertThat(replacement.fence()).isGreaterThan(lease.fence());
        assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.RUNNING);
    }
    @Test void expiryAndMissingFilesDoNotEraseCompletionOutcome() throws IOException {
        var j=create(admin);new TransferWorker(jobs,store).runExportOnce((job,out,progress)->out.write(1));
        UUID key=jdbc.queryForObject("SELECT artifact_id FROM transfer_completion WHERE job_id=?",UUID.class,j.id());
        store.delete(key);new ArtifactReconciler(jobs,store).reconcile();
        assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.READY);
        assertThat(jobs.status(admin,j.id()).errorCode()).isEqualTo(Failure.ARTIFACT_MISSING);
        assertThat(jdbc.queryForObject("SELECT state FROM transfer_artifact WHERE id=?",String.class,key)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_completion WHERE job_id=?",Integer.class,j.id())).isEqualTo(1);
    }
    @Test void workerPersistsIntentAndFailureWithoutPayloadDiagnostics() {
        var j=create(admin);
        assertThat(new TransferWorker(jobs,store).runExportOnce((job,out,progress)-> {
            assertThat(jobs.status(admin,job.id()).state()).isEqualTo(State.RUNNING);
            out.write(1);throw new IOException("private source body and secret");
        })).isTrue();
        assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.FAILED);
        assertThat(jdbc.queryForObject("SELECT error_code FROM transfer_job WHERE id=?",String.class,j.id())).isEqualTo("WORK_FAILED");
        assertThat(jdbc.queryForList("SELECT event FROM transfer_audit",String.class)).doesNotContain("private source body and secret");
    }
    @Test void stagingMappingsBelongToAJobAndRejectStaleWorkers() {
        var j=jobs.create(admin,Kind.COMPANY_IMPORT,UUID.randomUUID(),"a".repeat(64));
        // Upcoming upload/dry-run stages drive this state; no live import API exists.
        jdbc.update("UPDATE transfer_job SET state='VALIDATING' WHERE id=?",j.id());
        var lease=jobs.claim(UUID.randomUUID()).orElseThrow().lease();UUID source=UUID.randomUUID(),local=UUID.randomUUID();
        assertThat(jobs.map(lease,"users",source,local)).isTrue();assertThat(jobs.map(lease,"users",source,local)).isTrue();
        assertThatThrownBy(()->jobs.map(lease,"users",source,UUID.randomUUID())).hasMessage("MAPPING_CONFLICT");
        expire(j.id());jobs.claim(UUID.randomUUID()).orElseThrow();
        assertThatThrownBy(()->jobs.map(lease,"users",UUID.randomUUID(),UUID.randomUUID())).hasMessage("STALE_LEASE");
    }
    @Test void orphanCleanupHasGracePeriodAndNeverTouchesUnknownPaths() throws IOException {
        UUID old=UUID.randomUUID(),fresh=UUID.randomUUID();store.write(old,10,o->o.write(1));store.write(fresh,10,o->o.write(2));
        Files.setLastModifiedTime(directory.resolve(old+".blob"),FileTime.from(Instant.now().minusSeconds(1000)));
        Files.writeString(directory.resolve("operator-note.txt"),"keep");
        new ArtifactReconciler(jobs,store).reconcile();
        assertThat(store.inspect(old)).isEmpty();assertThat(store.inspect(fresh)).isPresent();assertThat(directory.resolve("operator-note.txt")).exists();
    }
    @Test void admissionReservesDiskAndRejectsDuplicateActiveJobs() {
        create(admin);assertThatThrownBy(()->create(admin)).hasMessage("ACTIVE_JOB_EXISTS");
        create(other);assertThatThrownBy(()->jobs.create(member,Kind.PERSONAL_EXPORT,UUID.randomUUID(),"a".repeat(64))).hasMessage("TRANSFER_QUOTA_EXCEEDED");
    }
    @Test void downloadAttemptsAreDistinctFromCompletedDelivery() {
        var j=create(admin);new TransferWorker(jobs,store).runExportOnce((job,out,progress)->out.write(1));
        jobs.recordDownload(admin,j.id(),false);assertThat(events(j.id(),"DOWNLOAD_COMPLETED")).isZero();
        jobs.recordDownload(admin,j.id(),true);assertThat(events(j.id(),"DOWNLOAD_ATTEMPT")).isEqualTo(1);assertThat(events(j.id(),"DOWNLOAD_COMPLETED")).isEqualTo(1);
        assertThatThrownBy(()->jobs.recordDownload(other,j.id(),false)).hasMessage("JOB_NOT_FOUND");
    }
    @Test void expiredArtifactsRevokeAvailabilityBeforeRetriedPhysicalCleanup() throws IOException {
        var j=create(admin);new TransferWorker(jobs,store).runExportOnce((job,out,progress)->out.write(1));
        UUID key=jdbc.queryForObject("SELECT artifact_id FROM transfer_completion WHERE job_id=?",UUID.class,j.id());
        jdbc.update("UPDATE transfer_artifact SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",key);
        ArtifactStore unavailableDelete=new ArtifactStore() {
            public Stored write(UUID k,long limit,Writer w)throws IOException{return store.write(k,limit,w);}
            public Optional<Stored> inspect(UUID k)throws IOException{return store.inspect(k);}
            public void delete(UUID k)throws IOException{throw new IOException("temporarily unavailable");}
            public Set<UUID> keysOlderThan(Instant t)throws IOException{return store.keysOlderThan(t);}
        };
        assertThatThrownBy(()->new ArtifactReconciler(jobs,unavailableDelete).reconcile()).isInstanceOf(IOException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM transfer_artifact WHERE id=?",String.class,key)).isEqualTo("DELETING");
        new ArtifactReconciler(jobs,store).reconcile();
        assertThat(store.inspect(key)).isEmpty();assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.READY);
        assertThat(events(j.id(),"CLEANUP_COMPLETED")).isEqualTo(1);
    }
    @Test void corruptedPublishedFileIsRemovedWithoutReplayingTheJob() throws IOException {
        var j=create(admin);new TransferWorker(jobs,store).runExportOnce((job,out,progress)->out.write(1));
        UUID key=jdbc.queryForObject("SELECT artifact_id FROM transfer_completion WHERE job_id=?",UUID.class,j.id());
        Files.write(directory.resolve(key+".blob"),new byte[]{2});
        new ArtifactReconciler(jobs,store).reconcile();
        assertThat(store.inspect(key)).isEmpty();assertThat(jobs.status(admin,j.id()).state()).isEqualTo(State.READY);
        assertThat(jobs.claim(UUID.randomUUID())).isEmpty();
    }
}
