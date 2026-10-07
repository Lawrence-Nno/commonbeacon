package com.lawrencenno.commonbeacon.identity.mail;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.lawrencenno.commonbeacon.identity.EmailOutbox.Type;
import com.lawrencenno.commonbeacon.identity.OutboxCrypto.Payload;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MailCaptureIT {
    @Test void allTemplatesTraverseRealSmtpAndLocalCaptureWithoutExternalCredentials() throws Exception {
        try (Network network = Network.newNetwork();
             var capture = new GenericContainer<>("axllent/mailpit:v1.27.4@sha256:df6c2541907e1be6fac21f509927cf6ed771617a1f4b361ef66d97bd05593d2d")
                .withNetwork(network).withExposedPorts(1025, 8025).withTmpFs(Map.of("/data", "rw"))
                .withEnv("MP_DATABASE", "/data/mailpit.db").withEnv("MP_DISABLE_VERSION_CHECK", "true")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(1025)),
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(8025))))
                .waitingFor(Wait.forHttp("/readyz").forPort(8025))) {
            capture.start();
            var settings = new MailSettings(MailSettingsTest.local(capture.getMappedPort(1025)), MailSettingsTest.keys());
            var templates = new MailTemplates(settings); var smtp = new SmtpMailTransport(settings);
            var expected = new HashMap<String, MailTransport.Message>();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + capture.getMappedPort(8025);
            for (Type type : Type.values()) {
                boolean link = type == Type.VERIFICATION || type == Type.PASSWORD_RESET || type == Type.EMAIL_CHANGE;
                UUID id = UUID.randomUUID();
                var message = templates.render(1, type, new Payload("frozen@commonbeacon.org", link ? "a".repeat(43) : ""), Instant.parse("2026-10-08T12:00:00Z"));
                assertThat(smtp.send(id, message)).isEqualTo(id);
                expected.put("<" + id + "@commonbeacon.org>", message);
            }
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/messages")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var messages = new JsonMapper().readTree(response.body()).get("messages");
            assertThat(messages.size()).isEqualTo(6);
            for (var summary : messages) {
                String id = summary.get("ID").asString();
                var raw = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + id + "/raw")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(raw.statusCode()).isEqualTo(200);
                var mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.body()));
                var message = expected.remove(mime.getMessageID()); assertThat(message).isNotNull();
                assertThat(mime.getSubject()).isEqualTo(message.subject());
                assertThat(mime.getAllRecipients()[0].toString()).isEqualTo(message.recipient());
                var body = (Multipart) mime.getContent();
                assertThat(body.getBodyPart(0).getContent().toString().replace("\r\n", "\n")).isEqualTo(message.text());
                assertThat(body.getBodyPart(1).getContent().toString()).isEqualTo(message.html());
            }
            assertThat(expected).isEmpty();
            // Both published ports are explicitly loopback-only; no local mail volume is mounted.
            var inspect = capture.getDockerClient().inspectContainerCmd(capture.getContainerId()).exec();
            for (var bindings : inspect.getNetworkSettings().getPorts().getBindings().values())
                for (var binding : bindings) assertThat(binding.getHostIp()).isEqualTo("127.0.0.1");
        }
    }
}
