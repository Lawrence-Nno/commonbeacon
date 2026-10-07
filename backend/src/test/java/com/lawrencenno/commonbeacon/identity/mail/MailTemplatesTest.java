package com.lawrencenno.commonbeacon.identity.mail;

import com.lawrencenno.commonbeacon.identity.EmailOutbox.Type;
import com.lawrencenno.commonbeacon.identity.OutboxCrypto.Payload;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MailTemplatesTest {
    private final MailTemplates templates = new MailTemplates(new MailSettings(MailSettingsTest.production(), MailSettingsTest.keys()));
    private final Instant expiry = Instant.parse("2026-10-08T12:00:00Z");
    @Test void allSixTemplatesHaveEquivalentTextAndEscapedHtmlAndFrozenRecipients() {
        for (Type type : Type.values()) {
            boolean link = type == Type.VERIFICATION || type == Type.PASSWORD_RESET || type == Type.EMAIL_CHANGE;
            var message = templates.render(1, type, new Payload("frozen@commonbeacon.org", link ? "a".repeat(43) : ""), expiry, "<script>&\"'" + "N".repeat(220));
            assertThat(message.recipient()).isEqualTo("frozen@commonbeacon.org");
            assertThat(message.html()).contains("lang=\"en\"", "<h1>", "&lt;script&gt;&amp;&quot;&#39;").doesNotContain("<script>");
            assertThat(message.text()).contains("<script>&\"'", "support@commonbeacon.org");
            // Every text paragraph has an escaped HTML counterpart (including the full fallback URL).
            for (String paragraph : message.text().split("\n\n")) {
                if (paragraph.contains("copy and paste") || paragraph.contains("#token=")) continue;
                String escaped = paragraph.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
                assertThat(message.html()).contains(escaped);
            }
            if (link) {
                assertThat(message.text()).contains("2026-10-08T12:00:00Z (UTC)", "ignore this message", "https://community.commonbeacon.org/");
                assertThat(message.html()).contains("#token=" + "a".repeat(43), "copy and paste");
            } else assertThat(message.text()).contains("If you did not make this change").doesNotContain("#token=", "expires");
        }
    }
    @Test void linksUseOnlyTrustedOriginAndCorrectPurposeRoutes() {
        Type[] types = {Type.VERIFICATION, Type.PASSWORD_RESET, Type.EMAIL_CHANGE};
        String[] routes = {"verify-email", "reset-password", "confirm-email-change"};
        for (int i = 0; i < types.length; i++) {
            var message = templates.render(1, types[i], new Payload("a@commonbeacon.org", "_".repeat(43)), expiry);
            assertThat(message.html()).contains("href=\"https://community.commonbeacon.org/" + routes[i] + "#token=" + "_".repeat(43) + "\"");
            assertThat(message.text()).doesNotContain("?token=", "redirect", "http://");
        }
    }
    @Test void unknownVersionsAndMalformedPurposePayloadsFailWithoutSecretEcho() {
        for (int version : new int[]{0, 2}) assertThatThrownBy(() -> templates.render(version, Type.VERIFICATION, new Payload("a@commonbeacon.org", "a".repeat(43)), expiry)).hasMessage("MAIL_INVALID_MESSAGE");
        assertThatThrownBy(() -> templates.render(1, Type.VERIFICATION, new Payload("a@commonbeacon.org", ""), expiry)).hasMessage("MAIL_INVALID_MESSAGE");
        assertThatThrownBy(() -> templates.render(1, Type.PASSWORD_CHANGED, new Payload("a@commonbeacon.org", "a".repeat(43)), expiry)).hasMessage("MAIL_INVALID_MESSAGE");
        assertThatThrownBy(() -> templates.render(1, Type.VERIFICATION, new Payload("a@commonbeacon.org", "a".repeat(43)), expiry, "bad\nname")).hasMessage("MAIL_INVALID_MESSAGE");
        assertThatThrownBy(() -> templates.render(1, Type.VERIFICATION, new Payload("not-email", "a".repeat(43)), expiry)).hasMessage("MAIL_INVALID_MESSAGE");
    }
    @Test void renderedMessagesAreRedactedInDiagnosticsAndJson() throws Exception {
        var message = templates.render(1, Type.PASSWORD_RESET, new Payload("a@commonbeacon.org", "a".repeat(43)), expiry);
        assertThat(message.toString()).isEqualTo("MailMessage[REDACTED]");
        assertThat(new JsonMapper().writeValueAsString(message)).isEqualTo("{}");
    }
}
