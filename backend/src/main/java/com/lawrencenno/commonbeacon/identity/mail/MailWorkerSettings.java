package com.lawrencenno.commonbeacon.identity.mail;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class MailWorkerSettings {
    private final boolean enabled;
    private final int batch, concurrency, hourly, interval, timeout, shutdown;
    public MailWorkerSettings(Environment env, MailSettings smtp) {
        try {
            String flag = env.getProperty("commonbeacon.email.worker.enabled", "false");
            if (!flag.equals("true") && !flag.equals("false")) throw new IllegalArgumentException();
            enabled = flag.equals("true");
            batch = value(env,"batch-size",20,1,20); concurrency = value(env,"concurrency",2,1,2);
            hourly = value(env,"attempts-per-hour",600,2,600); interval = value(env,"interval-ms",1000,100,60000);
            timeout = value(env,"timeout-ms",15000,100,15000); shutdown = value(env,"shutdown-ms",20000,100,20000);
            if (enabled && !smtp.enabled()) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalStateException("INVALID_MAIL_WORKER_CONFIGURATION"); }
    }
    private static int value(Environment env,String name,int fallback,int min,int max) {
        int value = env.getProperty("commonbeacon.email.worker."+name,Integer.class,fallback);
        if (value<min || value>max) throw new IllegalArgumentException(); return value;
    }
    public boolean enabled(){return enabled;} public int batch(){return batch;} public int concurrency(){return concurrency;}
    public int hourly(){return hourly;} public int interval(){return interval;} public int timeout(){return timeout;} public int shutdown(){return shutdown;}
}
