package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.AppUser;
import com.lawrencenno.commonbeacon.identity.AccountState;
import com.lawrencenno.commonbeacon.identity.VerificationRollout;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IdentitySchemaIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entities;
    @Autowired VerificationRollout rollout;

    UUID user(String state) {
        UUID id=UUID.randomUUID();
        boolean credentialed=!state.equals("IMPORTED_INACTIVE") && !state.equals("ERASED");
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,account_state) VALUES (?,?,?,?,?)",
            id,credentialed?id+"@example.test":null,state.equals("ERASED")?"Deleted member":"Schema member",credentialed?"test-only-hash":null,state);
        return id;
    }
    void rollback(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{action.run();tx.setRollbackOnly();});
    }
    void challenge(UUID subject,String purpose,String email,long generation) {
        jdbc.update("""
            INSERT INTO email_challenge(id,subject_id,purpose,intended_email,token_digest,generation,expires_at)
            VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP+interval '1 day')
            """,UUID.randomUUID(),subject,purpose,email,UUID.randomUUID().toString().replace("-","").repeat(2),generation);
    }
    void pending(UUID subject) {
        jdbc.update("UPDATE app_user SET email_verified_at=CURRENT_TIMESTAMP,email_change_generation=1 WHERE id=?",subject);
        jdbc.update("INSERT INTO pending_email_change(subject_id,intended_email,generation,expires_at) VALUES (?,'proposed@example.test',1,CURRENT_TIMESTAMP+interval '2 days')",subject);
    }
    @Test void newStatesValidateThroughHibernateWithoutInventingVerification() {
        assertThat(rollout.mode()).isEqualTo(VerificationRollout.Mode.TRANSITION);
        UUID id=user("PENDING_VERIFICATION");
        try {
            try(var em=entities.createEntityManager()) {
                var account=em.find(AppUser.class,id);
                assertThat(account.getAccountState()).isEqualTo(AccountState.PENDING_VERIFICATION);
                assertThat(account.isEmailVerified()).isFalse();
                assertThat(account.isActive()).isFalse();
            }
        } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
    @Test void pendingCannotBePrivilegedOrAlreadyVerified() {
        for(String assignment:new String[]{"role='ADMINISTRATOR'","email_verified_at=CURRENT_TIMESTAMP","email=NULL","password_hash=NULL"}) {
            UUID id=user("PENDING_VERIFICATION");
            try {assertThatThrownBy(()->jdbc.update("UPDATE app_user SET "+assignment+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);}
            finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
        }
    }
    @Test void importedAndErasedCannotAcquireCredentialsOrProof() {
        for(String state:new String[]{"IMPORTED_INACTIVE","ERASED"}) {
            UUID id=user(state);
            try {
                for(String assignment:new String[]{"email='forged@example.test'","password_hash='forged'","account_state='ACTIVE',email='forged@example.test',password_hash='forged'"})
                    assertThatThrownBy(()->jdbc.update("UPDATE app_user SET "+assignment+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
                if(state.equals("IMPORTED_INACTIVE"))assertThatThrownBy(()->jdbc.update("UPDATE app_user SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
                else {
                    jdbc.update("UPDATE app_user SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?",id);
                    assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM app_user WHERE id=?",Boolean.class,id)).isTrue();
                }
                assertThatThrownBy(()->challenge(id,"VERIFICATION","forged@example.test",1)).isInstanceOf(DataIntegrityViolationException.class);
            } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
        }
    }
    @Test void generationsAreIndependentOfSessionAndTransferRevisions() {
        rollback(()->{
            UUID id=user("ACTIVE");
            jdbc.update("UPDATE app_user SET verification_generation=1,password_reset_generation=2,email_change_generation=3 WHERE id=?",id);
            assertThat(jdbc.queryForMap("SELECT auth_epoch,auth_revision FROM app_user WHERE id=?",id)).containsEntry("auth_epoch",0L).containsEntry("auth_revision",0L);
            jdbc.update("UPDATE app_user SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?",id);
            assertThat(jdbc.queryForMap("SELECT auth_epoch,auth_revision,verification_generation,password_reset_generation,email_change_generation FROM app_user WHERE id=?",id))
                .containsEntry("auth_epoch",1L).containsEntry("auth_revision",1L).containsEntry("verification_generation",1L).containsEntry("password_reset_generation",2L).containsEntry("email_change_generation",3L);
            jdbc.update("UPDATE app_user SET auth_epoch=0 WHERE id=?",id);
            assertThat(jdbc.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Long.class,id)).isEqualTo(1L);
        });
    }
    @Test void changedLoginAddressLosesOldProof() {
        rollback(()->{
            UUID id=user("ACTIVE");pending(id);
            jdbc.update("UPDATE app_user SET email='replacement@example.test' WHERE id=?",id);
            assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM app_user WHERE id=?",Boolean.class,id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Long.class,id)).isEqualTo(2L);
        });
    }
    @Test void onlyOneUnrevokedChallengePerPurposeAndPurposeGenerationsDoNotConflict() {
        UUID id=user("ACTIVE");
        try {
            jdbc.update("UPDATE app_user SET verification_generation=1,password_reset_generation=1 WHERE id=?",id);
            challenge(id,"VERIFICATION",id+"@example.test",1);
            challenge(id,"PASSWORD_RESET",id+"@example.test",1);
            assertThatThrownBy(()->challenge(id,"VERIFICATION",id+"@example.test",1)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE email_challenge SET revoked_at=CURRENT_TIMESTAMP WHERE subject_id=? AND purpose='VERIFICATION'",id);
            assertThatThrownBy(()->jdbc.update("UPDATE email_challenge SET revoked_at=NULL WHERE subject_id=? AND purpose='VERIFICATION'",id)).isInstanceOf(DataIntegrityViolationException.class);
            challenge(id,"VERIFICATION",id+"@example.test",1);
        } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
    @Test void wrongAddressGenerationDigestExpiryAndAttemptsAreRejected() {
        UUID id=user("ACTIVE");
        try {
            jdbc.update("UPDATE app_user SET verification_generation=1 WHERE id=?",id);
            assertThatThrownBy(()->challenge(id,"VERIFICATION","wrong@example.test",1)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->challenge(id,"VERIFICATION",id+"@example.test",2)).isInstanceOf(DataIntegrityViolationException.class);
            challenge(id,"VERIFICATION",id+"@example.test",1);
            for(String assignment:new String[]{"token_digest='raw-token'","expires_at=created_at","attempts=11","consumed_at=CURRENT_TIMESTAMP,revoked_at=CURRENT_TIMESTAMP"})
                assertThatThrownBy(()->jdbc.update("UPDATE email_challenge SET "+assignment+" WHERE subject_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
    @Test void proposedAddressStaysPrivateAndDoesNotChangeLogin() {
        rollback(()->{
            UUID id=user("ACTIVE");pending(id);
            challenge(id,"EMAIL_CHANGE","proposed@example.test",1);
            assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id=?",String.class,id)).isEqualTo(id+"@example.test");
        });
    }
    @Test void pendingEmailRequiresVerifiedActiveSubjectAndBoundChallenge() {
        UUID id=user("ACTIVE");
        try {
            jdbc.update("UPDATE app_user SET email_change_generation=1 WHERE id=?",id);
            assertThatThrownBy(()->jdbc.update("INSERT INTO pending_email_change(subject_id,intended_email,generation,expires_at) VALUES (?,'new@example.test',1,CURRENT_TIMESTAMP+interval '1 day')",id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->challenge(id,"EMAIL_CHANGE","new@example.test",1)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
    @Test void erasureAndSuspensionPurgePrivateIdentityStorage() {
        for(String state:new String[]{"ERASED","SUSPENDED"})rollback(()->{
            UUID id=user("ACTIVE");pending(id);challenge(id,"EMAIL_CHANGE","proposed@example.test",1);
            if(state.equals("ERASED"))jdbc.update("UPDATE app_user SET account_state='ERASED',email=NULL,password_hash=NULL,display_name='Deleted member' WHERE id=?",id);
            else jdbc.update("UPDATE app_user SET account_state='SUSPENDED' WHERE id=?",id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=?",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_email_change WHERE subject_id=?",Long.class,id)).isZero();
        });
    }
    @Test void transactionRollbackPreservesAccountAndChallengeTogether() {
        UUID id=user("ACTIVE");
        try {
            rollback(()->{pending(id);challenge(id,"EMAIL_CHANGE","proposed@example.test",1);});
            assertThat(jdbc.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM app_user WHERE id=?",Boolean.class,id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=?",Long.class,id)).isZero();
        } finally {jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
}
