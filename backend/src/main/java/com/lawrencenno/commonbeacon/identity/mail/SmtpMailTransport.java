package com.lawrencenno.commonbeacon.identity.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import java.net.SocketTimeoutException;
import java.util.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.*;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One SMTP attempt, outside database transactions. Retries belong to the durable worker. */
@Component
public final class SmtpMailTransport implements MailTransport {
    private final MailSettings settings;
    private final JavaMailSenderImpl sender;
    public SmtpMailTransport(MailSettings settings) {
        this.settings = settings;
        sender = new JavaMailSenderImpl();
        sender.setHost(settings.host()); sender.setPort(settings.port());
        String protocol = settings.tls() == MailSettings.Tls.IMPLICIT ? "smtps" : "smtp";
        sender.setProtocol(protocol);
        if (!settings.username().isEmpty()) { sender.setUsername(settings.username()); sender.setPassword(settings.password()); }
        Properties props = sender.getJavaMailProperties();
        String prefix = "mail." + protocol + ".";
        props.setProperty("mail.debug", "false");
        props.setProperty(prefix + "auth", Boolean.toString(!settings.username().isEmpty()));
        props.setProperty(prefix + "connectiontimeout", Integer.toString(settings.connectTimeout()));
        props.setProperty(prefix + "timeout", Integer.toString(settings.readTimeout()));
        props.setProperty(prefix + "writetimeout", Integer.toString(settings.writeTimeout()));
        props.setProperty(prefix + "ssl.checkserveridentity", "true");
        props.setProperty(prefix + "starttls.enable", Boolean.toString(settings.tls() == MailSettings.Tls.STARTTLS));
        props.setProperty(prefix + "starttls.required", Boolean.toString(settings.tls() == MailSettings.Tls.STARTTLS));
        props.setProperty(prefix + "quitwait", "false");
        props.setProperty(prefix + "sendpartial", "false");
    }
    @Override public UUID send(UUID outboxId, Message message) {
        try (var attempt = new MailAttempt(java.time.Duration.ofSeconds(15))) { return send(outboxId,message,attempt); }
    }
    @Override public UUID send(UUID outboxId, Message message, MailAttempt attempt) {
        settings.requireEnabled();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("MAIL_SEND_TRANSACTION_FORBIDDEN");
        if (outboxId == null || message == null) throw new TransportFailure(Failure.INVALID_MESSAGE);
        attempt.check();
        var deliverySender = new JavaMailSenderImpl();
        deliverySender.setHost(sender.getHost()); deliverySender.setPort(sender.getPort()); deliverySender.setProtocol(sender.getProtocol());
        deliverySender.setUsername(sender.getUsername()); deliverySender.setPassword(sender.getPassword());
        var props = new Properties(); props.putAll(sender.getJavaMailProperties());
        String prefix = "mail." + sender.getProtocol() + ".";
        props.put(prefix + "socketFactory", DeadlineSockets.plain(attempt,settings.connectTimeout()));
        props.put(prefix + "ssl.socketFactory", DeadlineSockets.tls(attempt,settings.connectTimeout()));
        props.setProperty(prefix + "socketFactory.fallback", "false");
        props.setProperty(prefix + "ssl.socketFactory.fallback", "false");
        deliverySender.setJavaMailProperties(props);
        try {
            String id = "<" + outboxId + "@" + settings.sender().substring(settings.sender().lastIndexOf('@') + 1) + ">";
            // Jakarta Mail saveChanges regenerates Message-ID; override to preserve durable correlation.
            MimeMessage mime = new MimeMessage(deliverySender.getSession()) {
                @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", id); }
            };
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(settings.sender()); helper.setTo(message.recipient());
            helper.setSubject(message.subject());
            var alternative = new MimeMultipart("alternative");
            var plain = new MimeBodyPart(); plain.setText(message.text(), "UTF-8");
            var html = new MimeBodyPart(); html.setText(message.html(), "UTF-8", "html");
            alternative.addBodyPart(plain); alternative.addBodyPart(html); mime.setContent(alternative);
            mime.setHeader("Auto-Submitted", "auto-generated");
            deliverySender.send(mime);
            attempt.check();
            return outboxId;
        } catch (MailException | MessagingException failed) {
            attempt.check();
            throw new TransportFailure(classify(failed));
        }
    }
    static Failure classify(Throwable root) {
        var queue = new ArrayDeque<Throwable>(); queue.add(root);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean timeout = false, permanent = false, config = false;
        while (!queue.isEmpty() && seen.size() < 32) {
            Throwable failure = queue.removeFirst(); if (!seen.add(failure)) continue;
            timeout |= failure instanceof SocketTimeoutException;
            config |= failure instanceof MailAuthenticationException || failure instanceof jakarta.mail.AuthenticationFailedException
                || failure instanceof javax.net.ssl.SSLException;
            int status = failure instanceof SMTPAddressFailedException address ? address.getReturnCode()
                : failure instanceof SMTPSendFailedException send ? send.getReturnCode() : 0;
            permanent |= status >= 500 && status <= 599;
            if (failure.getCause() != null) queue.add(failure.getCause());
            if (failure instanceof MessagingException mail && mail.getNextException() != null) queue.add(mail.getNextException());
            if (failure instanceof MailSendException mail) Collections.addAll(queue, mail.getMessageExceptions());
        }
        return config ? Failure.CONFIGURATION : timeout ? Failure.TIMEOUT : permanent ? Failure.PERMANENT : Failure.TRANSIENT;
    }
    @Override public String toString() { return "SmtpMailTransport[REDACTED]"; }
}
