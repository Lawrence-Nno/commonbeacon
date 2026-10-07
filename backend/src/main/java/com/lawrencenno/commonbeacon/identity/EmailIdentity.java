package com.lawrencenno.commonbeacon.identity;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

/** Internal orchestration for future HTTP flows; never returns a challenge or delivery receipt. */
@Service
public class EmailIdentity {
    private final EmailChallenges challenges;private final EmailOutbox outbox;private final OutboxCrypto crypto;
    public EmailIdentity(EmailChallenges challenges,EmailOutbox outbox,OutboxCrypto crypto){this.challenges=challenges;this.outbox=outbox;this.crypto=crypto;}
    public boolean issue(UUID subject,EmailChallenges.Purpose purpose,String email,BooleanSupplier admitted){crypto.requireEnabled();return challenges.issue(subject,purpose,email,admitted,outbox::challenge);}
    public void consume(EmailChallenges.Purpose purpose,String token,String password){crypto.requireEnabled();challenges.consume(purpose,token,password,outbox::completed);}
}
