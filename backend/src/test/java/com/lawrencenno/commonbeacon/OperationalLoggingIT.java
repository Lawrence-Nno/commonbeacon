package com.lawrencenno.commonbeacon;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"commonbeacon.demo.enabled=false", "commonbeacon.transfer.storage.enabled=false"})
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class OperationalLoggingIT {
    @LocalServerPort int port;

    @Test void serializesSafeFailureDiagnosticsAsJson(CapturedOutput output) throws Exception {
        var failure = new IllegalStateException("do-not-log-password", new java.io.IOException("do-not-log-path"));
        var job = java.util.UUID.randomUUID();
        com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(
                org.slf4j.LoggerFactory.getLogger(OperationalLoggingIT.class), "test.failure", failure, job);
        var line = output.getOut().lines().filter(text -> text.startsWith("{") && text.contains(job.toString())).findFirst().orElseThrow();
        var log = new ObjectMapper().readTree(line);
        assertThat(log.path("level").asText()).isEqualTo("ERROR");
        assertThat(log.path("exceptionTypes").size()).isEqualTo(2);
        assertThat(log.path("codeLocations").size()).isPositive();
        assertThat(log.has("stack_trace")).isFalse();
        assertThat(line).doesNotContain("do-not-log-password", "do-not-log-path");
    }

    @Test void emitsJsonAndCorrelatesSecurityRejectionsAndSuccess(CapturedOutput output) throws Exception {
        var mapper = new ObjectMapper();
        var client = HttpClient.newHttpClient();
        for (var path : new String[]{"/actuator/health", "/api/v1/admin/data/jobs"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Request-Id", "untrusted-secret").GET().build(), HttpResponse.BodyHandlers.ofString());
            String id = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(id).matches("[0-9a-f-]{36}").isNotEqualTo("untrusted-secret");
            if (path.contains("admin")) {
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(mapper.readTree(response.body()).path("requestId").asText()).isEqualTo(id);
            } else assertThat(response.statusCode()).isEqualTo(200);
            // The response can reach the client just before the filter's finally block logs completion.
            org.awaitility.Awaitility.await().untilAsserted(() -> {
                var lines = output.getOut().lines().filter(line -> line.startsWith("{") && line.contains(id)).toList();
                assertThat(lines).hasSize(1);
                var log = mapper.readTree(lines.getFirst());
                assertThat(log.path("event").asText()).isEqualTo("http.request_completed");
                assertThat(log.path("status").asInt()).isEqualTo(response.statusCode());
                assertThat(log.path("requestId").asText()).isEqualTo(id);
                assertThat(log.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            });
        }
        assertThat(output.getOut()).doesNotContain("untrusted-secret");
    }
}
