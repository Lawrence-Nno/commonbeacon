package com.lawrencenno.commonbeacon.identity.mail;

import com.lawrencenno.commonbeacon.identity.EmailOutbox;
import com.lawrencenno.commonbeacon.shared.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Dedicated poll/send pools; SMTP never blocks identity transactions or other schedulers. */
@Component
public final class EmailWorker implements SmartLifecycle {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(EmailWorker.class);
    private final EmailOutbox outbox; private final MailTemplates templates; private final MailTransport transport; private final MailWorkerSettings settings;
    private final UUID owner=UUID.randomUUID(); private final AtomicBoolean polling=new AtomicBoolean();
    private final ConcurrentHashMap<UUID,MailAttempt> active=new ConcurrentHashMap<>();
    private volatile boolean running; private ExecutorService sends; private ScheduledExecutorService poller;
    private Semaphore slots; private long cycles,claims;
    public EmailWorker(EmailOutbox outbox,MailTemplates templates,MailTransport transport,MailWorkerSettings settings){this.outbox=outbox;this.templates=templates;this.transport=transport;this.settings=settings;}
    @Override public synchronized void start(){
        if(running || !settings.enabled())return;
        if(sends!=null && !sends.isTerminated())throw new IllegalStateException("MAIL_WORKER_STOPPING");
        slots=new Semaphore(settings.concurrency());
        sends=Executors.newFixedThreadPool(settings.concurrency(),task->{var thread=new Thread(task,"mail-send");thread.setDaemon(true);return thread;});
        poller=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"mail-poll");thread.setDaemon(true);return thread;});
        running=true;poller.scheduleWithFixedDelay(this::runOnce,settings.interval(),settings.interval(),TimeUnit.MILLISECONDS);
    }
    /** Public internal/test seam, never exposed through a controller. */
    public int runOnce(){
        if(!running || !polling.compareAndSet(false,true))return 0;
        int started=0;
        try{
            // Cleanup is independent: failure must not prevent a subsequent delivery attempt.
            if(cycles++%60==0){try{outbox.purgeExpired();outbox.pruneBudgets();}catch(RuntimeException failure){report("mail.cleanup_failed",failure,null);}}
            for(int scan=0;scan<settings.batch() && running && slots.tryAcquire();scan++){
                boolean handedOff=false;
                try{
                    var claim=outbox.claim(owner,settings.hourly(),claims++%4==0);
                    if(claim.isEmpty())break;
                    var lease=claim.get();
                    if(!running) {outbox.failed(lease,EmailOutbox.Failure.TRANSIENT);break;}
                    sends.execute(()->deliver(lease));handedOff=true;started++;
                }catch(RuntimeException failure){report("mail.claim_failed",failure,null);break;}
                finally{if(!handedOff)slots.release();}
            }
        }finally{polling.set(false);}
        return started;
    }
    private void deliver(EmailOutbox.Lease lease){
        try(var attempt=new MailAttempt(Duration.ofMillis(settings.timeout()))){
            active.put(lease.id(),attempt);
            try{
                if(!running)attempt.cancel();
                attempt.check();
                var delivery=outbox.prepareDelivery(lease);if(delivery==null)return;
                var message=templates.render(delivery.version(),delivery.type(),delivery.payload(),delivery.expires());
                UUID correlation=transport.send(lease.id(),message,attempt);
                attempt.check();
                if(outbox.accepted(lease,correlation))LOG.atInfo().addKeyValue("event","mail.accepted").addKeyValue("jobId",lease.id()).log("SMTP accepted account mail");
            }catch(MailTransport.TransportFailure failure){
                var kind=failure.failure()==MailTransport.Failure.INVALID_MESSAGE?EmailOutbox.Failure.PAYLOAD_INVALID:EmailOutbox.Failure.valueOf(failure.failure().name());
                try{outbox.failed(lease,kind);LOG.atWarn().addKeyValue("event","mail.attempt_failed").addKeyValue("jobId",lease.id()).addKeyValue("failureCode",kind.name()).log("Account mail attempt failed");}
                catch(RuntimeException finalization){report("mail.finalization_failed",finalization,lease.id());}
            }catch(RuntimeException failure){
                // Acceptance/finalization may already have happened remotely. Keep the
                // lease/payload for conservative recovery; do not blindly send again now.
                report("mail.finalization_failed",failure,lease.id());
            }finally{active.remove(lease.id());}
        }finally{slots.release();}
    }
    private static void report(String event,RuntimeException failure,UUID id){
        if(failure instanceof ApiFailure api && (api.code().equals("IMPORT_IN_PROGRESS") || api.code().equals("ERASURE_IN_PROGRESS")))return;
        OperationalLogs.failure(LOG,event,failure,id);
    }
    public int inFlight(){return settings.concurrency()-(slots==null?settings.concurrency():slots.availablePermits());}
    @Override public void stop(){
        synchronized(this){if(!running)return;running=false;poller.shutdownNow();sends.shutdown();}
        try{if(!sends.awaitTermination(settings.shutdown(),TimeUnit.MILLISECONDS))abortActiveSends();}
        catch(InterruptedException interrupted){abortActiveSends();Thread.currentThread().interrupt();}
    }
    private void abortActiveSends(){
        active.values().forEach(MailAttempt::cancel);
        // shutdownNow returns tasks that will never run their delivery finally block.
        // Release their local permits; keep their durable leases for restart recovery.
        slots.release(sends.shutdownNow().size());
    }
    @Override public void stop(Runnable callback){Thread.ofVirtual().start(()->{try{stop();}finally{callback.run();}});}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE;}
}
