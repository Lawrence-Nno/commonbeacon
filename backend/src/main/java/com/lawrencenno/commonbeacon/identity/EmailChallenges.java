package com.lawrencenno.commonbeacon.identity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.MigrationGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal transactional primitive. Never expose its secret-bearing objects as API responses.
 * Callers supply database-only outbox callbacks; sending mail belongs outside the transaction. */
@Service
public class EmailChallenges {
    public enum Purpose {
        VERIFICATION("verification_generation",Duration.ofHours(24)),
        PASSWORD_RESET("password_reset_generation",Duration.ofMinutes(30)),
        EMAIL_CHANGE("email_change_generation",Duration.ofMinutes(30));
        final String column; final Duration lifetime;
        Purpose(String column,Duration lifetime){this.column=column;this.lifetime=lifetime;}
    }
    @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE,getterVisibility=JsonAutoDetect.Visibility.NONE,isGetterVisibility=JsonAutoDetect.Visibility.NONE)
    public static final class Issued {
        private final UUID id,subject; private final Purpose purpose; private final String email,token;
        private final long generation; private final Instant expires;
        private Issued(UUID id,UUID subject,Purpose purpose,String email,String token,long generation,Instant expires){
            this.id=id;this.subject=subject;this.purpose=purpose;this.email=email;this.token=token;this.generation=generation;this.expires=expires;
        }
        public UUID id(){return id;} public UUID subject(){return subject;} public Purpose purpose(){return purpose;}
        public String email(){return email;} public String token(){return token;} public long generation(){return generation;} public Instant expires(){return expires;}
        @Override public String toString(){return "Issued[REDACTED]";}
    }
    @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE,getterVisibility=JsonAutoDetect.Visibility.NONE,isGetterVisibility=JsonAutoDetect.Visibility.NONE)
    public static final class Completion {
        private final UUID subject; private final Purpose purpose; private final String previousEmail,email;
        private Completion(UUID subject,Purpose purpose,String previousEmail,String email){this.subject=subject;this.purpose=purpose;this.previousEmail=previousEmail;this.email=email;}
        public UUID subject(){return subject;} public Purpose purpose(){return purpose;}
        public String previousEmail(){return previousEmail;} public String email(){return email;}
        @Override public String toString(){return "Completion[REDACTED]";}
    }
    private record Account(UUID id,String email,String state,boolean verified,long generation) {
        @Override public String toString(){return "Account[REDACTED]";}
    }
    private record Stored(UUID id,UUID subject,String email,long generation,Instant created,Instant expires,boolean terminal) {
        @Override public String toString(){return "Stored[REDACTED]";}
    }
    private final JdbcTemplate jdbc; private final Clock clock; private final SecureRandom random;
    private final PasswordEncoder passwords; private final TransactionTemplate transaction;
    public EmailChallenges(JdbcTemplate jdbc,PlatformTransactionManager manager,@Qualifier("challengeClock") Clock clock,
            @Qualifier("challengeRandom") SecureRandom random,PasswordEncoder passwords){
        this.jdbc=jdbc;this.clock=clock;this.random=random;this.passwords=passwords;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(10);
    }
    private void gates(){
        MigrationGate.shared(jdbc);
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,AccountPolicy.IDENTITY_KEY);
    }
    private Account account(UUID subject,Purpose purpose){
        return jdbc.query("SELECT id,email,account_state,email_verified_at IS NOT NULL,"+purpose.column+" FROM app_user WHERE id=? FOR UPDATE",
            (r,n)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4),r.getLong(5)),subject).stream().findFirst().orElse(null);
    }
    private static boolean eligible(Account a,Purpose purpose){
        if(a==null || !(a.state.equals("ACTIVE") || a.state.equals("PENDING_VERIFICATION")))return false;
        return switch(purpose){case VERIFICATION -> !a.verified;case PASSWORD_RESET -> true;case EMAIL_CHANGE -> a.state.equals("ACTIVE") && a.verified;};
    }
    private static String normalized(String email){
        if(email==null || email.length()>254)return null;
        String value=email.trim().toLowerCase(Locale.ROOT);
        return value.length()>=3 && value.indexOf('@')>0 && value.chars().noneMatch(c->c<=32 || c>=127)?value:null;
    }
    private static String digest(Purpose purpose,String token){
        if(token==null || token.length()!=43 || !token.matches("[A-Za-z0-9_-]{43}"))return null;
        try {
            byte[] bytes=Base64.getUrlDecoder().decode(token);
            if(bytes.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token))return null;
            var hash=MessageDigest.getInstance("SHA-256");
            hash.update(("CommonBeacon/email-challenge/v1/"+purpose.name()+"\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash.digest(bytes));
        }catch(IllegalArgumentException invalid){return null;}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("CHALLENGE_DIGEST_UNAVAILABLE");}
    }
    private static ApiFailure invalid(){return new ApiFailure(400,"INVALID_EMAIL_LINK","This email link is invalid or has expired.");}
    private void revoke(UUID subject,Instant now,String purposes){
        jdbc.update("UPDATE email_challenge SET revoked_at=? WHERE subject_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND purpose IN ("+purposes+")",Timestamp.from(now),subject);
    }
    /** False is an internal generic no-op outcome. Admission must not send mail or log private values. */
    public boolean issue(UUID subject,Purpose purpose,String intendedEmail,BooleanSupplier admission,Consumer<Issued> intent){
        Objects.requireNonNull(subject);Objects.requireNonNull(purpose);Objects.requireNonNull(admission);Objects.requireNonNull(intent);
        String email=normalized(intendedEmail);if(email==null)return false;
        return Boolean.TRUE.equals(transaction.execute(tx->{
            gates();var a=account(subject,purpose);Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(!eligible(a,purpose) || (purpose==Purpose.EMAIL_CHANGE?email.equals(a.email):!email.equals(a.email)))return false;
            var recent=jdbc.queryForList("SELECT created_at FROM email_challenge WHERE subject_id=? AND purpose=? ORDER BY created_at DESC LIMIT 1",Timestamp.class,subject,purpose.name());
            if(!recent.isEmpty() && now.isBefore(recent.getFirst().toInstant().plusSeconds(60)))return false;
            if(!admission.getAsBoolean())return false;
            long generation=Math.addExact(a.generation,1);Instant expires=now.plus(purpose.lifetime);
            revoke(subject,now,"'"+purpose.name()+"'");
            jdbc.update("UPDATE app_user SET "+purpose.column+"=? WHERE id=?",generation,subject);
            if(purpose==Purpose.EMAIL_CHANGE)jdbc.update("""
                INSERT INTO pending_email_change(subject_id,intended_email,generation,created_at,expires_at) VALUES (?,?,?,?,?)
                ON CONFLICT(subject_id) DO UPDATE SET intended_email=EXCLUDED.intended_email,generation=EXCLUDED.generation,created_at=EXCLUDED.created_at,expires_at=EXCLUDED.expires_at
                """,subject,email,generation,Timestamp.from(now),Timestamp.from(expires));
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO email_challenge(id,subject_id,purpose,intended_email,token_digest,generation,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                id,subject,purpose.name(),email,digest(purpose,token),generation,Timestamp.from(now),Timestamp.from(expires));
            intent.accept(new Issued(id,subject,purpose,email,token,generation,expires));return true;
        }));
    }
    /** Verification of pending accounts requires an inbox-owner-selected replacement password.
     * Reset preserves state/proof. Email change keeps the old address until this transaction commits.
     * The completion callback must persist required security/outbox intents in this same transaction. */
    public void consume(Purpose purpose,String token,String newPassword,Consumer<Completion> intent){
        Objects.requireNonNull(purpose);Objects.requireNonNull(intent);
        String hash=digest(purpose,token);if(hash==null)throw invalid();
        try {transaction.executeWithoutResult(tx->{
            gates();
            // Locate without row locking first; global identity serialization precedes subject/challenge locks.
            var subjects=jdbc.queryForList("SELECT subject_id FROM email_challenge WHERE token_digest=? AND purpose=?",UUID.class,hash,purpose.name());
            if(subjects.isEmpty())throw invalid();UUID subject=subjects.getFirst();var a=account(subject,purpose);
            var rows=jdbc.query("SELECT id,subject_id,intended_email,generation,created_at,expires_at,consumed_at IS NOT NULL OR revoked_at IS NOT NULL FROM email_challenge WHERE token_digest=? AND purpose=? FOR UPDATE",
                (r,n)->new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getTimestamp(5).toInstant(),r.getTimestamp(6).toInstant(),r.getBoolean(7)),hash,purpose.name());
            if(rows.isEmpty())throw invalid();var c=rows.getFirst();Instant now=clock.instant();
            if(!eligible(a,purpose) || c.terminal || c.generation!=a.generation || now.isBefore(c.created) || !now.isBefore(c.expires))throw invalid();
            if(purpose==Purpose.EMAIL_CHANGE){
                if(c.email.equals(a.email) || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pending_email_change WHERE subject_id=? AND intended_email=? AND generation=? AND expires_at>?)",
                    Boolean.class,subject,c.email,c.generation,Timestamp.from(now))))throw invalid();
            }else if(!c.email.equals(a.email))throw invalid();
            boolean replace=purpose==Purpose.PASSWORD_RESET || purpose==Purpose.VERIFICATION && a.state.equals("PENDING_VERIFICATION");
            if(replace && (newPassword==null || newPassword.isBlank() || newPassword.length()<12 || newPassword.length()>128))
                throw new ApiFailure(400,"INVALID_PASSWORD","Choose a password between 12 and 128 characters.");
            String encoded=replace?passwords.encode(newPassword):null;
            // Mark before changing the address; the schema validates the original binding here.
            if(jdbc.update("UPDATE email_challenge SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL",Timestamp.from(now),c.id)!=1)throw invalid();
            switch(purpose){
                case VERIFICATION -> {
                    if(replace)jdbc.update("UPDATE app_user SET account_state='ACTIVE',email_verified_at=?,password_hash=? WHERE id=?",Timestamp.from(now),encoded,subject);
                    else jdbc.update("UPDATE app_user SET email_verified_at=? WHERE id=?",Timestamp.from(now),subject);
                    revoke(subject,now,"'VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE'");
                }
                case PASSWORD_RESET -> {
                    jdbc.update("UPDATE app_user SET password_hash=?,auth_epoch=auth_epoch+1 WHERE id=?",encoded,subject);
                    revoke(subject,now,"'PASSWORD_RESET','EMAIL_CHANGE'");
                }
                case EMAIL_CHANGE -> {
                    // The existing proof trigger clears unchanged proof when an address changes.
                    // Ensure explicit new-address proof differs even with a fixed/coarse clock.
                    jdbc.update("UPDATE app_user SET email=?,email_verified_at=CASE WHEN email_verified_at=? THEN email_verified_at+interval '1 microsecond' ELSE ? END WHERE id=?",
                        c.email,Timestamp.from(now),Timestamp.from(now),subject);
                    revoke(subject,now,"'VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE'");
                }
            }
            jdbc.update("DELETE FROM pending_email_change WHERE subject_id=?",subject);
            intent.accept(new Completion(subject,purpose,a.email,purpose==Purpose.EMAIL_CHANGE?c.email:a.email));
        });}catch(DataIntegrityViolationException conflict){throw invalid();}
    }
}
