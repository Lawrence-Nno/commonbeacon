package com.lawrencenno.commonbeacon.identity.mail;

import com.lawrencenno.commonbeacon.identity.OutboxCrypto;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Deployment configuration only; never accepts request headers or redirect destinations. */
@Component
public final class MailSettings {
    public enum Tls { STARTTLS, IMPLICIT, NONE }
    private final boolean enabled;
    private final String host, sender, support, username, password, origin;
    private final int port, connectTimeout, readTimeout, writeTimeout;
    private final Tls tls;

    public MailSettings(Environment env, OutboxCrypto crypto) {
        try {
            String switchValue = value(env, "enabled", "false");
            if (!switchValue.equals("true") && !switchValue.equals("false")) fail();
            enabled = Boolean.parseBoolean(switchValue);
            host = value(env, "host", ""); sender = value(env, "sender", "");
            support = value(env, "support", ""); username = value(env, "username", "");
            password = value(env, "password", ""); origin = value(env, "public-origin", "");
            port = Integer.parseInt(value(env, "port", "587"));
            connectTimeout = timeout(env, "connect-timeout-ms");
            readTimeout = timeout(env, "read-timeout-ms");
            writeTimeout = timeout(env, "write-timeout-ms");
            tls = Tls.valueOf(value(env, "tls", "STARTTLS"));
            boolean local = env.matchesProfiles("local") && !env.matchesProfiles("prod");
            // Every deployment other than explicit local capture is production-safe by default.
            if (!local && env.getProperty("commonbeacon.demo.enabled", Boolean.class, false)) fail();
            if (enabled) {
                if (!crypto.enabled() || port < 1 || port > 65535 || host.length() > 253
                    || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) fail();
                address(sender); address(support);
                if (username.length() > 254 || username.chars().anyMatch(c -> c < 32 || c == 127)
                    || password.length() > 1024 || password.chars().anyMatch(c -> c < 32 || c == 127)) fail();
                if (username.isEmpty() != password.isEmpty()) fail();
                URI url = URI.create(origin);
                if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
                    || url.getFragment() != null || !(url.getRawPath().isEmpty())
                    || url.getPort() == 0 || url.getPort() > 65535
                    || !(url.getScheme().equals("https") || local && url.getScheme().equals("http"))) fail();
                if (origin.length() > 300 || origin.chars().anyMatch(c -> c <= 32 || c >= 127)) fail();
                String publicHost = url.getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
                if (!local && (tls == Tls.NONE || username.isBlank() || password.isBlank()
                    || publicHost.equals("localhost") || publicHost.endsWith(".localhost")
                    || publicHost.endsWith(".local") || publicHost.endsWith(".test")
                    || publicHost.endsWith(".invalid") || publicHost.endsWith(".example")
                    || publicHost.matches("(?:.*\\.)?example\\.(?:com|net|org)")
                    || !publicHost.contains(".") || publicHost.matches("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")
                    || publicHost.contains(":"))) fail();
                if (local && tls == Tls.NONE && (!username.isEmpty() || !password.isEmpty())) fail();
            }
        } catch (Exception invalid) {
            // Do not retain a parsing exception that could contain credentials or URL secrets.
            throw new IllegalStateException("INVALID_MAIL_CONFIGURATION");
        }
    }
    private static String value(Environment env, String name, String fallback) {
        return env.getProperty("commonbeacon.email.smtp." + name, fallback);
    }
    private static int timeout(Environment env, String name) {
        int ms = Integer.parseInt(value(env, name, "10000"));
        if (ms < 100 || ms > 15000) fail();
        return ms;
    }
    private static void fail() { throw new IllegalArgumentException(); }
    public static void address(String value) {
        try {
            if (value == null || value.length() > 254 || value.length() < 3
                || value.chars().anyMatch(c -> c <= 32 || c >= 127)) fail();
            InternetAddress parsed = new InternetAddress(value, true);
            parsed.validate();
            if (parsed.getPersonal() != null || parsed.isGroup() || !value.equals(parsed.getAddress())
                || !value.matches("[^<>(),;:\\\"\\[\\]\\\\]+@[^<>(),;:\\\"\\[\\]\\\\]+\\.[^<>(),;:\\\"\\[\\]\\\\]+")) fail();
        } catch (Exception invalid) { throw new IllegalArgumentException("INVALID_MAIL_ADDRESS"); }
    }
    public void requireEnabled() { if (!enabled) throw new IllegalStateException("MAIL_TRANSPORT_DISABLED"); }
    public boolean enabled() { return enabled; }
    public String host() { return host; }
    public String sender() { return sender; }
    public String support() { return support; }
    public String username() { return username; }
    public String password() { return password; }
    public String origin() { return origin; }
    public int port() { return port; }
    public int connectTimeout() { return connectTimeout; }
    public int readTimeout() { return readTimeout; }
    public int writeTimeout() { return writeTimeout; }
    public Tls tls() { return tls; }
    @Override public String toString() { return "MailSettings[REDACTED]"; }
}
