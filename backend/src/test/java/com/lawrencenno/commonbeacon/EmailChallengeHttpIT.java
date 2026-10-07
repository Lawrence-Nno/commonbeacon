package com.lawrencenno.commonbeacon;

import com.lawrencenno.commonbeacon.identity.EmailChallenges;
import com.lawrencenno.commonbeacon.identity.EmailChallenges.Purpose;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"commonbeacon.demo.enabled=false","commonbeacon.transfer.storage.enabled=false"})
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EmailChallengeHttpIT {
    @LocalServerPort int port;
    @Autowired EmailChallenges challenges;
    @Autowired JdbcTemplate jdbc;
    @Test void linkVisitsNeverConsumeAndRequestDiagnosticsOmitSecrets(CapturedOutput output) throws Exception {
        UUID id=UUID.randomUUID();String email=id+"@example.test";AtomicReference<EmailChallenges.Issued> issued=new AtomicReference<>();
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,account_state) VALUES (?,?,?,'test-only-hash','PENDING_VERIFICATION')",id,email,"HTTP challenge member");
        try {
            assertThat(challenges.issue(id,Purpose.VERIFICATION,email,()->true,issued::set)).isTrue();String token=issued.get().token();
            var client=HttpClient.newHttpClient();String body="{\"token\":\""+token+"\",\"password\":\"private-replacement-password\"}";
            for(String method:new String[]{"GET","HEAD","POST"}){
                var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/email-verification?token="+token))
                    .header("Content-Type","application/json").method(method,method.equals("POST")?HttpRequest.BodyPublishers.ofString(body):HttpRequest.BodyPublishers.noBody()).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isIn(401,403,404);
                assertThat(response.body()).doesNotContain(token,email,"private-replacement-password");
                String requestId=response.headers().firstValue("X-Request-Id").orElseThrow();
                org.awaitility.Awaitility.await().untilAsserted(()->assertThat(output.getOut()).contains(requestId));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE subject_id=? AND consumed_at IS NULL AND revoked_at IS NULL",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT account_state FROM app_user WHERE id=?",String.class,id)).isEqualTo("PENDING_VERIFICATION");
            assertThat(output.getAll()).doesNotContain(token,email,"private-replacement-password");
        }finally{jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
}
