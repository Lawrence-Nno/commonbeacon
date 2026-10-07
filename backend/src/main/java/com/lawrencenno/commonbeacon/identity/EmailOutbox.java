package com.lawrencenno.commonbeacon.identity;

import com.lawrencenno.commonbeacon.shared.MigrationGate;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Durable outbox primitives. All network calls remain outside these transactions. */
@Service
public class EmailOutbox {
    public enum Type { VERIFICATION,PASSWORD_RESET,EMAIL_CHANGE,PASSWORD_CHANGED,EMAIL_CHANGED_OLD,EMAIL_CHANGED_NEW }
    public enum Failure { TRANSIENT,TIMEOUT,PERMANENT,CONFIGURATION,SUPPRESSED,KEY_UNAVAILABLE,PAYLOAD_INVALID }
    public record Lease(UUID id,UUID owner,long version) {}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,getterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,isGetterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public record Delivery(Type type,int version,Instant expires,OutboxCrypto.Payload payload) {
        @Override public String toString(){return "MailDelivery[REDACTED]";}
    }
    private record Row(UUID id,UUID subject,Type type,UUID event,UUID challenge,Long generation,String key,byte[] nonce,byte[] ciphertext,
        Instant created,Instant expires,String state,int attempts,UUID owner,long version,Instant leaseUntil) {
        String binding(){return "CommonBeacon/mail/v1/"+id+"/"+subject+"/"+type+"/"+event+"/"+challenge+"/"+generation+"/"+created+"/"+expires;}
        @Override public String toString(){return "OutboxRow[REDACTED]";}
    }
    private final JdbcTemplate jdbc;private final OutboxCrypto crypto;private final Clock clock;private final TransactionTemplate tx;
    public EmailOutbox(JdbcTemplate jdbc,PlatformTransactionManager manager,OutboxCrypto crypto,@Qualifier("challengeClock") Clock clock){
        this.jdbc=jdbc;this.crypto=crypto;this.clock=clock;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private <T>T locked(Supplier<T> action){return tx.execute(status->{MigrationGate.shared(jdbc);jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,AccountPolicy.IDENTITY_KEY);return action.get();});}
    private Row row(UUID id){return jdbc.query("SELECT * FROM email_outbox WHERE id=? FOR UPDATE",(r,n)->new Row(r.getObject("id",UUID.class),r.getObject("subject_id",UUID.class),Type.valueOf(r.getString("message_type")),r.getObject("event_id",UUID.class),r.getObject("challenge_id",UUID.class),r.getObject("generation",Long.class),r.getString("key_id"),r.getBytes("nonce"),r.getBytes("ciphertext"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("state"),r.getInt("attempts"),r.getObject("lease_owner",UUID.class),r.getLong("lease_version"),r.getTimestamp("lease_until")==null?null:r.getTimestamp("lease_until").toInstant()),id).stream().findFirst().orElse(null);}
    private void persist(UUID subject,Type type,UUID event,UUID challenge,Long generation,Instant expires,OutboxCrypto.Payload payload){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("MAIL_INTENT_TRANSACTION_REQUIRED");
        // Called after the caller owns maintenance/identity/subject locks; do not invert them here.
        crypto.requireEnabled();
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM email_outbox WHERE subject_id=? AND message_type=? AND event_id=?)",Boolean.class,subject,type.name(),event)))return;
        UUID id=UUID.randomUUID();Instant created=now();var r=new Row(id,subject,type,event,challenge,generation,null,null,null,created,expires,"QUEUED",0,null,0,null);
        var encrypted=crypto.encrypt(r.binding(),payload);
        jdbc.update("INSERT INTO email_outbox(id,subject_id,message_type,event_id,template_version,challenge_id,generation,key_id,nonce,ciphertext,next_attempt_at,created_at,updated_at,expires_at) VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?)",
            id,subject,type.name(),event,challenge,generation,encrypted.key(),encrypted.nonce(),encrypted.body(),Timestamp.from(created),Timestamp.from(created),Timestamp.from(created),Timestamp.from(expires));
    }
    public void challenge(EmailChallenges.Issued c){persist(c.subject(),Type.valueOf(c.purpose().name()),c.id(),c.id(),c.generation(),c.expires(),new OutboxCrypto.Payload(c.email(),c.token()));}
    public void completed(EmailChallenges.Completion c){
        Instant expires=now().plus(Duration.ofHours(24));
        if(c.passwordChanged())persist(c.subject(),Type.PASSWORD_CHANGED,c.challenge(),null,null,expires,new OutboxCrypto.Payload(c.email(),""));
        if(c.purpose()==EmailChallenges.Purpose.EMAIL_CHANGE){
            persist(c.subject(),Type.EMAIL_CHANGED_OLD,c.challenge(),null,null,expires,new OutboxCrypto.Payload(c.previousEmail(),""));
            persist(c.subject(),Type.EMAIL_CHANGED_NEW,c.challenge(),null,null,expires,new OutboxCrypto.Payload(c.email(),""));
        }
    }
    private boolean eligible(Row r){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT email_outbox_eligible(?,?)",Boolean.class,r.id,Timestamp.from(now())));}
    private void terminal(Row r,String state,String failure){jdbc.update("UPDATE email_outbox SET state=?,failure_code=?,nonce=NULL,ciphertext=NULL,lease_owner=NULL,lease_until=NULL,lease_version=lease_version+1,updated_at=? WHERE id=?",state,failure,Timestamp.from(now().isBefore(r.created)?r.created:now()),r.id);}
    /** A single durable claim; eligibility/decryption happen in a subsequent short transaction. */
    public Optional<Lease> claim(UUID worker){return claim(worker,600,false);}
    public Optional<Lease> claim(UUID worker,int hourlyLimit,boolean preferAlert){crypto.requireEnabled();
        if(worker==null || hourlyLimit<2 || hourlyLimit>600)throw new IllegalArgumentException("INVALID_MAIL_CLAIM");
        return locked(()->{
        Instant now=now();
        for(UUID id:jdbc.queryForList("SELECT id FROM email_outbox WHERE state='SENDING' AND lease_until<=? ORDER BY lease_until,id LIMIT 20 FOR UPDATE SKIP LOCKED",UUID.class,Timestamp.from(now)))retry(row(id),"LEASE_EXPIRED");
        if(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE state='SENDING'",Long.class)>=2)return Optional.empty();
        Instant window=now.truncatedTo(ChronoUnit.HOURS);
        jdbc.update("INSERT INTO email_transport_budget(window_start) VALUES (?) ON CONFLICT DO NOTHING",Timestamp.from(window));
        var budget=jdbc.queryForMap("SELECT ordinary,alerts,retries FROM email_transport_budget WHERE window_start=? FOR UPDATE",Timestamp.from(window));
        int ordinary=((Number)budget.get("ordinary")).intValue(),alerts=((Number)budget.get("alerts")).intValue(),retries=((Number)budget.get("retries")).intValue();
        int ordinaryLimit=Math.max(1,hourlyLimit*4/5),alertLimit=hourlyLimit-ordinaryLimit,retryLimit=Math.max(1,hourlyLimit*3/10);
        if(ordinary+alerts>=hourlyLimit)return Optional.empty();
        var ids=jdbc.queryForList("SELECT id FROM email_outbox WHERE state IN ('QUEUED','RETRY_WAIT') AND created_at<=? AND next_attempt_at<=? AND (attempts=0 OR ?) AND ((generation IS NULL AND ?) OR (generation IS NOT NULL AND ?)) ORDER BY CASE WHEN (generation IS NULL)=? THEN 0 ELSE 1 END,next_attempt_at,id LIMIT 20 FOR UPDATE SKIP LOCKED",UUID.class,Timestamp.from(now),Timestamp.from(now),retries<retryLimit,alerts<alertLimit,ordinary<ordinaryLimit,preferAlert);
        for(UUID id:ids){var r=row(id);
        if(!now.isBefore(r.expires)){terminal(r,"EXPIRED",null);continue;}
        if(!eligible(r)){terminal(r,"CANCELLED","STALE_CHALLENGE");continue;}
        if(r.attempts>=6){terminal(r,"FAILED","ATTEMPTS_EXHAUSTED");continue;}
        jdbc.update("UPDATE email_transport_budget SET ordinary=ordinary+?,alerts=alerts+?,retries=retries+? WHERE window_start=?",r.generation==null?0:1,r.generation==null?1:0,r.attempts>0?1:0,Timestamp.from(window));
        jdbc.update("UPDATE email_outbox SET state='SENDING',attempts=attempts+1,lease_owner=?,lease_until=?,lease_version=lease_version+1,updated_at=? WHERE id=?",worker,Timestamp.from(now.plusSeconds(60)),Timestamp.from(now),r.id);
        return Optional.of(new Lease(r.id,worker,r.version+1));
        }
        return Optional.empty();
    });}
    private boolean owned(Row r,Lease lease){Instant now=now();return r!=null && r.state.equals("SENDING") && Objects.equals(r.owner,lease.owner) && r.version==lease.version && !now.isBefore(r.created) && now.isBefore(r.leaseUntil);}
    /** Null means cancelled/expired/fenced or crypto failure. No lock survives the return. */
    public OutboxCrypto.Payload prepare(Lease lease){var delivery=prepareDelivery(lease);return delivery==null?null:delivery.payload();}
    public Delivery prepareDelivery(Lease lease){return locked(()->{
        var r=row(lease.id);if(!owned(r,lease))return null;
        if(!now().isBefore(r.expires)){terminal(r,"EXPIRED",null);return null;}
        if(!eligible(r)){terminal(r,"CANCELLED","STALE_CHALLENGE");return null;}
        try{return new Delivery(r.type,1,r.expires,crypto.decrypt(r.binding(),r.key,r.nonce,r.ciphertext));}catch(IllegalStateException invalid){terminal(r,"FAILED",invalid.getMessage().equals("KEY_UNAVAILABLE")?"KEY_UNAVAILABLE":"PAYLOAD_INVALID");return null;}
    });}
    /** Provider acceptance is not proof of inbox delivery. The exact payload is purged here. */
    public boolean accepted(Lease lease,UUID correlation){return locked(()->{var r=row(lease.id);if(!owned(r,lease))return false;terminal(r,"ACCEPTED",null);jdbc.update("UPDATE email_outbox SET provider_correlation=? WHERE id=?",correlation,r.id);return true;});}
    public boolean failed(Lease lease,Failure failure){return locked(()->{
        var r=row(lease.id);if(!owned(r,lease))return false;
        if(!now().isBefore(r.expires)){terminal(r,"EXPIRED",null);return true;}
        if(!eligible(r)){terminal(r,"CANCELLED","STALE_CHALLENGE");return true;}
        if(failure!=Failure.TRANSIENT && failure!=Failure.TIMEOUT){terminal(r,"FAILED",failure.name());return true;}
        retry(r,failure.name());return true;
    });}
    private void retry(Row r,String code){
        if(!now().isBefore(r.expires)){terminal(r,"EXPIRED",null);return;}
        if(!eligible(r)){terminal(r,"CANCELLED","STALE_CHALLENGE");return;}
        if(r.attempts>=6){terminal(r,"FAILED","ATTEMPTS_EXHAUSTED");return;}
        long[] delays={30,120,600,1800,7200};long delay=delays[r.attempts-1];
        // Bounded positive jitter; retries preserve nonce/ciphertext and never extend expiry.
        long jitter=java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,delay/10));
        Instant next=now().plusSeconds(delay+jitter);
        if(!next.isBefore(r.expires)){terminal(r,"EXPIRED",null);return;}
        jdbc.update("UPDATE email_outbox SET state='RETRY_WAIT',failure_code=?,next_attempt_at=?,lease_owner=NULL,lease_until=NULL,lease_version=lease_version+1,updated_at=? WHERE id=?",code,Timestamp.from(next),Timestamp.from(now()),r.id);
    }
    public int purgeExpired(){return locked(()->{
        Instant now=now();int count=jdbc.update("UPDATE email_outbox SET state='EXPIRED',nonce=NULL,ciphertext=NULL,lease_owner=NULL,lease_until=NULL,lease_version=lease_version+1,updated_at=greatest(created_at,?) WHERE id IN (SELECT id FROM email_outbox WHERE state IN ('QUEUED','SENDING','RETRY_WAIT') AND expires_at<=? ORDER BY id LIMIT 200)",Timestamp.from(now),Timestamp.from(now));
        count+=jdbc.update("DELETE FROM email_outbox WHERE id IN (SELECT id FROM email_outbox WHERE state IN ('ACCEPTED','FAILED','CANCELLED','EXPIRED') AND updated_at<? ORDER BY id LIMIT 200)",Timestamp.from(now.minus(Duration.ofDays(30))));return count;
    });}
    public int pruneBudgets(){return locked(()->jdbc.update("DELETE FROM email_transport_budget WHERE window_start IN (SELECT window_start FROM email_transport_budget WHERE window_start<? ORDER BY window_start LIMIT 200)",Timestamp.from(now().minus(Duration.ofDays(2)))));}
}
