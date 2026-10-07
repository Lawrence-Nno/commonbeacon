package com.lawrencenno.commonbeacon.identity.mail;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.UUID;

public interface MailTransport {
    enum Failure { TIMEOUT, TRANSIENT, PERMANENT, CONFIGURATION, SUPPRESSED, INVALID_MESSAGE }
    final class TransportFailure extends RuntimeException {
        private final Failure failure;
        public TransportFailure(Failure failure) { super("MAIL_" + failure.name()); this.failure = failure; }
        public Failure failure() { return failure; }
    }
    @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE,getterVisibility=JsonAutoDetect.Visibility.NONE,isGetterVisibility=JsonAutoDetect.Visibility.NONE)
    final class Message {
        private final String recipient, subject, text, html;
        public Message(String recipient, String subject, String text, String html) {
            try {
                MailSettings.address(recipient);
                if (subject == null || subject.isBlank() || subject.length() > 160
                    || subject.chars().anyMatch(c -> c < 32 || c == 127)
                    || text == null || html == null || text.isBlank() || html.isBlank()
                    || text.length() > 16000 || html.length() > 24000) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new TransportFailure(Failure.INVALID_MESSAGE); }
            this.recipient = recipient; this.subject = subject; this.text = text; this.html = html;
        }
        public String recipient() { return recipient; }
        public String subject() { return subject; }
        public String text() { return text; }
        public String html() { return html; }
        @Override public String toString() { return "MailMessage[REDACTED]"; }
    }
    /** Caller supplies the durable outbox ID. Acceptance is not delivery or inbox verification. */
    UUID send(UUID outboxId, Message message);
    default UUID send(UUID outboxId, Message message, MailAttempt attempt) {
        attempt.check(); UUID receipt = send(outboxId, message); attempt.check(); return receipt;
    }
}
