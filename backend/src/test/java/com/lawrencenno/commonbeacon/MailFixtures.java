package com.lawrencenno.commonbeacon;
import com.lawrencenno.commonbeacon.identity.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
/** Disposable fixture keys only; never deployment keys or real recipients. */
final class MailFixtures {
    static EmailChallenges.Issued queue(JdbcTemplate jdbc,PlatformTransactionManager manager,PasswordEncoder passwords,UUID subject){
        var clock=Clock.systemUTC();var crypto=new OutboxCrypto(true,"fixture","fixture="+Base64.getEncoder().encodeToString(new byte[32]));
        var outbox=new EmailOutbox(jdbc,manager,crypto,clock);var challenges=new EmailChallenges(jdbc,manager,clock,new SecureRandom(),passwords);
        var issued=new AtomicReference<EmailChallenges.Issued>();
        if(!challenges.issue(subject,EmailChallenges.Purpose.PASSWORD_RESET,jdbc.queryForObject("SELECT email FROM app_user WHERE id=?",String.class,subject),()->true,c->{outbox.challenge(c);issued.set(c);}))throw new AssertionError("Disposable mail fixture refused");
        return issued.get();
    }
}
