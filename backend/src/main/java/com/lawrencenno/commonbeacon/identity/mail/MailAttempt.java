package com.lawrencenno.commonbeacon.identity.mail;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deadline/shutdown closes registered sockets instead of abandoning a live send thread. */
public final class MailAttempt implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "mail-deadline"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final ConcurrentLinkedQueue<AutoCloseable> resources = new ConcurrentLinkedQueue<>();
    private final AtomicReference<MailTransport.Failure> failure = new AtomicReference<>();
    private final ScheduledFuture<?> deadline;
    public MailAttempt(Duration limit) {
        if (limit.isNegative() || limit.isZero() || limit.compareTo(Duration.ofSeconds(15)) > 0) throw new IllegalArgumentException("INVALID_MAIL_DEADLINE");
        deadline = DEADLINES.schedule(() -> cancel(MailTransport.Failure.TIMEOUT), limit.toMillis(), TimeUnit.MILLISECONDS);
    }
    public <T extends AutoCloseable> T watch(T resource) {
        resources.add(resource);
        if (failure.get() != null) { closeResources(); check(); }
        return resource;
    }
    public void check() { if (failure.get() != null) throw new MailTransport.TransportFailure(failure.get()); }
    public void cancel() { cancel(MailTransport.Failure.TRANSIENT); }
    private void cancel(MailTransport.Failure reason) { failure.compareAndSet(null, reason); closeResources(); }
    private void closeResources() {
        AutoCloseable resource;
        while ((resource = resources.poll()) != null) try { resource.close(); } catch (Exception ignored) { /* Never log provider/socket errors. */ }
    }
    @Override public void close() { deadline.cancel(false); closeResources(); }
    @Override public String toString() { return "MailAttempt[REDACTED]"; }
}
