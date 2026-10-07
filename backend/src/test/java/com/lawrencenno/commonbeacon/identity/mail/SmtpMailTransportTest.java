package com.lawrencenno.commonbeacon.identity.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class SmtpMailTransportTest {
    private MailTransport.Message message() { return new MailTransport.Message("user@commonbeacon.org", "Test subject", "Test text", "<p>Test HTML</p>"); }
    @Test void realSmtpSerializesMultipartAndKeepsStableMessageIdAcrossAttempts() throws Exception {
        try (var smtp = new Fixture(250, false, 2)) {
            var transport = new SmtpMailTransport(new MailSettings(MailSettingsTest.local(smtp.port()), MailSettingsTest.keys()));
            UUID id = UUID.randomUUID();
            assertThat(transport.send(id, message())).isEqualTo(id); assertThat(transport.send(id, message())).isEqualTo(id);
            List<String> bodies = smtp.result(); assertThat(bodies).hasSize(2);
            for (String body : bodies) {
                MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                assertThat(mime.getMessageID()).isEqualTo("<" + id + "@commonbeacon.org>");
                assertThat(mime.getFrom()[0].toString()).isEqualTo("accounts@commonbeacon.org");
                assertThat(mime.getSubject()).isEqualTo("Test subject");
                var content = (Multipart) mime.getContent();
                assertThat(content.getCount()).isEqualTo(2);
                assertThat(content.getBodyPart(0).getContent().toString()).isEqualTo("Test text");
                assertThat(content.getBodyPart(1).getContent().toString()).isEqualTo("<p>Test HTML</p>");
            }
        }
    }
    @Test void realSmtpClassifiesTransientAndPermanentRecipientRejectionWithoutBodies() throws Exception {
        for (int status : new int[]{450, 550}) try (var smtp = new Fixture(status, false, 1)) {
            var transport = new SmtpMailTransport(new MailSettings(MailSettingsTest.local(smtp.port()), MailSettingsTest.keys()));
            assertThatThrownBy(() -> transport.send(UUID.randomUUID(), message())).isInstanceOf(MailTransport.TransportFailure.class)
                .hasMessage(status == 450 ? "MAIL_TRANSIENT" : "MAIL_PERMANENT").hasNoCause();
        }
    }
    @Test void actualServerGreetingTimeoutIsBoundedAndRedacted() throws Exception {
        try (var smtp = new Fixture(250, true, 1)) {
            var env = MailSettingsTest.local(smtp.port()).withProperty("commonbeacon.email.smtp.read-timeout-ms", "150");
            var transport = new SmtpMailTransport(new MailSettings(env, MailSettingsTest.keys()));
            long started = System.nanoTime();
            assertThatThrownBy(() -> transport.send(UUID.randomUUID(), message())).hasMessage("MAIL_TIMEOUT").hasNoCause();
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(3);
        }
    }
    @Test void requiredStarttlsNeverFallsBackToPlaintext() throws Exception {
        try (var smtp = new Fixture(250, false, 1)) {
            var env = MailSettingsTest.local(smtp.port()).withProperty("commonbeacon.email.smtp.tls", "STARTTLS");
            assertThatThrownBy(() -> new SmtpMailTransport(new MailSettings(env, MailSettingsTest.keys())).send(UUID.randomUUID(), message())).hasMessage("MAIL_TRANSIENT");
            assertThat(smtp.result()).isEmpty();
        }
    }
    @Test void exceptionGraphClassificationNeverRetainsProviderSecrets() throws Exception {
        var timeout = new MessagingException("private recipient and token", new SocketTimeoutException("private host"));
        assertThat(SmtpMailTransport.classify(new MailSendException("private SMTP body", timeout))).isEqualTo(MailTransport.Failure.TIMEOUT);
        assertThat(SmtpMailTransport.classify(new MailAuthenticationException("secret password"))).isEqualTo(MailTransport.Failure.CONFIGURATION);
        assertThat(SmtpMailTransport.classify(new MessagingException("bad certificate", new javax.net.ssl.SSLHandshakeException("secret host")))).isEqualTo(MailTransport.Failure.CONFIGURATION);
        assertThat(SmtpMailTransport.classify(new SMTPAddressFailedException(new InternetAddress("user@commonbeacon.org"), "RCPT", 550, "private rejection"))).isEqualTo(MailTransport.Failure.PERMANENT);
    }
    @Test void networkSendIsForbiddenInsideDatabaseTransactionAndDisabledByDefault() {
        var transport = new SmtpMailTransport(new MailSettings(MailSettingsTest.local(1), MailSettingsTest.keys()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> transport.send(UUID.randomUUID(), message())).hasMessage("MAIL_SEND_TRANSACTION_FORBIDDEN"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        var disabled = new SmtpMailTransport(new MailSettings(new org.springframework.mock.env.MockEnvironment(), new com.lawrencenno.commonbeacon.identity.OutboxCrypto(false, "", "")));
        assertThatThrownBy(() -> disabled.send(UUID.randomUUID(), message())).hasMessage("MAIL_TRANSPORT_DISABLED");
    }
    /** Loopback-only controllable SMTP peer. Never relays and only accepts test data. */
    private static final class Fixture implements AutoCloseable {
        private final ServerSocket server;
        private volatile Socket active;
        private final FutureTask<List<String>> task;
        Fixture(int status, boolean stall, int connections) throws Exception {
            server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress()); server.setSoTimeout(4000);
            task = new FutureTask<>(() -> {
                var bodies = new ArrayList<String>();
                for (int i = 0; i < connections; i++) try (Socket socket = server.accept()) {
                    active = socket; socket.setSoTimeout(4000);
                    if (stall) { socket.getInputStream().read(); continue; }
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    output.print("220 test fixture\r\n"); output.flush();
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (line.startsWith("DATA")) {
                            output.print("354 send data\r\n"); output.flush(); var body = new StringBuilder();
                            while ((line = input.readLine()) != null && !line.equals(".")) body.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                            bodies.add(body.toString()); output.print("250 accepted\r\n");
                        } else if (line.startsWith("RCPT") && status != 250) output.print(status + " private rejection detail\r\n");
                        else if (line.equals("QUIT")) { output.print("221 bye\r\n"); output.flush(); break; }
                        else output.print("250 ok\r\n");
                        output.flush();
                    }
                }
                return bodies;
            });
            Thread.ofVirtual().start(task);
        }
        int port() { return server.getLocalPort(); }
        List<String> result() throws Exception { return task.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws Exception { server.close(); if (active != null) active.close(); task.cancel(true); }
    }
}
