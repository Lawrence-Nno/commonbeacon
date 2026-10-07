package com.lawrencenno.commonbeacon.identity.mail;

import com.lawrencenno.commonbeacon.identity.EmailOutbox.Type;
import com.lawrencenno.commonbeacon.identity.OutboxCrypto.Payload;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** Immutable version 1 templates. No request-derived origins, passwords, or community data. */
@Component
public final class MailTemplates {
    private final MailSettings settings;
    public MailTemplates(MailSettings settings) { this.settings = settings; }
    public MailTransport.Message render(int version, Type type, Payload payload, Instant expires) {
        return render(version, type, payload, expires, "");
    }
    public MailTransport.Message render(int version, Type type, Payload payload, Instant expires, String name) {
        settings.requireEnabled();
        if (version != 1 || type == null || payload == null || expires == null || name == null || name.length() > 256
            || name.chars().anyMatch(c -> c < 32 || c == 127)) throw new MailTransport.TransportFailure(MailTransport.Failure.INVALID_MESSAGE);
        boolean link = type == Type.VERIFICATION || type == Type.PASSWORD_RESET || type == Type.EMAIL_CHANGE;
        if (link != !payload.token().isEmpty()) throw new MailTransport.TransportFailure(MailTransport.Failure.INVALID_MESSAGE);
        String subject = switch (type) {
            case VERIFICATION -> "Verify your CommonBeacon email";
            case PASSWORD_RESET -> "Reset your CommonBeacon password";
            case EMAIL_CHANGE -> "Confirm your new CommonBeacon email";
            case PASSWORD_CHANGED -> "Your CommonBeacon password changed";
            case EMAIL_CHANGED_OLD, EMAIL_CHANGED_NEW -> "Your CommonBeacon email changed";
        };
        String action = switch (type) {
            case VERIFICATION -> "Verify email";
            case PASSWORD_RESET -> "Reset password";
            case EMAIL_CHANGE -> "Confirm new email";
            default -> "";
        };
        var paragraphs = new ArrayList<String>();
        paragraphs.add(name.isBlank() ? "Hello," : "Hello " + name + ",");
        paragraphs.add(switch (type) {
            case VERIFICATION -> "Confirm that this email address belongs to you to complete your CommonBeacon email verification.";
            case PASSWORD_RESET -> "Someone requested a password reset for your CommonBeacon account. Open the link and choose a new password to continue.";
            case EMAIL_CHANGE -> "Confirm that this new email address belongs to you. Your current address stays in place until you confirm the change.";
            case PASSWORD_CHANGED -> "Your CommonBeacon account password has changed. If you made this change, no further action is needed.";
            case EMAIL_CHANGED_OLD -> "Your CommonBeacon account email address has changed. This notice was sent to the previous address for your security.";
            case EMAIL_CHANGED_NEW -> "Your CommonBeacon account now uses this verified email address. If you made this change, no further action is needed.";
        });
        String url = "";
        if (link) {
            String route = switch (type) { case VERIFICATION -> "/verify-email"; case PASSWORD_RESET -> "/reset-password"; default -> "/confirm-email-change"; };
            url = settings.origin() + route + "#token=" + payload.token();
            paragraphs.add("This link expires at " + DateTimeFormatter.ISO_INSTANT.format(expires) + " (UTC). Opening it does not change your account; you must submit the confirmation form.");
            paragraphs.add("If you did not request this, ignore this message. Your account will not change from opening or ignoring the link.");
        } else paragraphs.add("If you did not make this change, contact support immediately for help securing your account.");
        paragraphs.add("Need help? Contact " + settings.support() + ".");
        StringBuilder text = new StringBuilder(subject).append("\n\n");
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>")
            .append(escape(subject)).append("</title></head><body><main><h1>").append(escape(subject)).append("</h1>");
        for (String paragraph : paragraphs) { text.append(paragraph).append("\n\n"); html.append("<p>").append(escape(paragraph)).append("</p>"); }
        if (link) {
            text.append(action).append(": ").append(url).append("\n\nIf the button does not work, copy and paste the full link above into your browser.\n");
            html.append("<p><a href=\"").append(escape(url)).append("\">").append(escape(action)).append("</a></p><p>If the button does not work, copy and paste this full link into your browser:</p><p style=\"overflow-wrap:anywhere\">").append(escape(url)).append("</p>");
        }
        html.append("</main></body></html>");
        return new MailTransport.Message(payload.recipient(), subject, text.toString(), html.toString());
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
