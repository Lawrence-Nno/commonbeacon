package com.lawrencenno.commonbeacon.identity.mail;

import com.lawrencenno.commonbeacon.identity.OutboxCrypto;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class MailSettingsTest {
    static OutboxCrypto keys() { return new OutboxCrypto(true, "test", "test=" + Base64.getEncoder().encodeToString(new byte[32])); }
    static MockEnvironment local(int port) {
        var env = production(); env.setActiveProfiles("local");
        return env.withProperty("commonbeacon.email.smtp.host", "127.0.0.1")
            .withProperty("commonbeacon.email.smtp.port", "" + port)
            .withProperty("commonbeacon.email.smtp.tls", "NONE")
            .withProperty("commonbeacon.email.smtp.username", "")
            .withProperty("commonbeacon.email.smtp.password", "")
            .withProperty("commonbeacon.email.smtp.public-origin", "http://127.0.0.1:4173");
    }
    static MockEnvironment production() {
        return new MockEnvironment().withProperty("commonbeacon.email.smtp.enabled", "true")
            .withProperty("commonbeacon.email.smtp.host", "smtp.service.test")
            .withProperty("commonbeacon.email.smtp.username", "smtp-user")
            .withProperty("commonbeacon.email.smtp.password", "smtp-secret")
            .withProperty("commonbeacon.email.smtp.sender", "accounts@commonbeacon.org")
            .withProperty("commonbeacon.email.smtp.support", "support@commonbeacon.org")
            .withProperty("commonbeacon.email.smtp.public-origin", "https://community.commonbeacon.org");
    }
    @Test void disabledDefaultsNeedNoProviderOrKeys() {
        assertThat(new MailSettings(new MockEnvironment(), new OutboxCrypto(false, "", "")).enabled()).isFalse();
    }
    @Test void enabledRequiresEverySettingAndExternalKeys() {
        for (String missing : new String[]{"host", "sender", "support", "public-origin", "username", "password"}) {
            var env = production().withProperty("commonbeacon.email.smtp." + missing, "");
            assertThatThrownBy(() -> new MailSettings(env, keys())).hasMessage("INVALID_MAIL_CONFIGURATION").hasNoCause();
        }
        assertThatThrownBy(() -> new MailSettings(production(), new OutboxCrypto(false, "", ""))).hasMessage("INVALID_MAIL_CONFIGURATION");
    }
    @Test void unsafeOriginsHeadersTlsPortsAndTimeoutsFailClosed() {
        for (String origin : new String[]{"http://community.commonbeacon.org", "https://localhost", "https://localhost.", "https://internal", "https://127.0.0.1", "https://0x7f.0.0.1", "https://[::1]", "https://example.com", "https://example.net", "https://example.org", "https://app.example", "https://community.example.com", "https://app.localhost", "https://app.test", "https://user:secret@community.commonbeacon.org", "https://community.commonbeacon.org:0", "https://community.commonbeacon.org/", "https://community.commonbeacon.org/path", "https://community.commonbeacon.org?next=evil", "https://community.commonbeacon.org#secret"})
            assertThatThrownBy(() -> new MailSettings(production().withProperty("commonbeacon.email.smtp.public-origin", origin), keys())).hasMessage("INVALID_MAIL_CONFIGURATION").hasNoCause();
        for (String[] bad : new String[][]{{"tls", "NONE"}, {"tls", "invalid"}, {"port", "0"}, {"port", "65536"}, {"connect-timeout-ms", "0"}, {"read-timeout-ms", "16000"}, {"write-timeout-ms", "-1"}, {"sender", "account@test.org\r\nBcc: stolen@test.org"}, {"support", "Name <support@test.org>"}, {"host", "smtp.test/path"}, {"username", "user\nsecret"}})
            assertThatThrownBy(() -> new MailSettings(production().withProperty("commonbeacon.email.smtp." + bad[0], bad[1]), keys())).hasMessage("INVALID_MAIL_CONFIGURATION").hasNoCause();
    }
    @Test void onlyExplicitLocalProfileAllowsCaptureAndProdWinsOverLocal() {
        assertThat(new MailSettings(local(1026), keys()).tls()).isEqualTo(MailSettings.Tls.NONE);
        var mixed = local(1026); mixed.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new MailSettings(mixed, keys())).hasMessage("INVALID_MAIL_CONFIGURATION");
        assertThatThrownBy(() -> new MailSettings(production().withProperty("commonbeacon.demo.enabled", "true"), keys())).hasMessage("INVALID_MAIL_CONFIGURATION");
        assertThat(new MailSettings(production().withProperty("commonbeacon.email.smtp.tls", "IMPLICIT"), keys()).tls()).isEqualTo(MailSettings.Tls.IMPLICIT);
    }
    @Test void plainMailboxValidationRejectsRecipientInjectionAndMultipleRecipients() {
        for (String value : new String[]{"no-at", "a@test.org,b@test.org", "a@test.org\r\nBcc:x@test.org", "<a@test.org>", "a@test.org ", "group:a@test.org;"})
            assertThatThrownBy(() -> MailSettings.address(value)).hasMessage("INVALID_MAIL_ADDRESS");
        MailSettings.address("user+tag@commonbeacon.org");
        assertThat(new MailSettings(production(), keys()).toString()).isEqualTo("MailSettings[REDACTED]");
    }
}
