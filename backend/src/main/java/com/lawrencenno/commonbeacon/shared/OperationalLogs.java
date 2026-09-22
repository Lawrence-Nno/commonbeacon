package com.lawrencenno.commonbeacon.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.slf4j.Logger;

/** Allowlisted diagnostics: never pass raw throwables, messages, payloads or paths to a logger. */
public final class OperationalLogs {
    private OperationalLogs() {}

    public static void failure(Logger logger, String event, Throwable failure, Object jobId) {
        var types = new ArrayList<String>();
        var frames = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var cause = failure; cause != null && types.size() < 8 && seen.add(cause); cause = cause.getCause()) {
            types.add(cause.getClass().getName());
            for (var frame : cause.getStackTrace()) {
                if (frames.size() < 24 && frame.getClassName().startsWith("com.lawrencenno.commonbeacon."))
                    frames.add(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            }
        }
        var log = logger.atError().addKeyValue("event", event)
                .addKeyValue("exceptionTypes", types).addKeyValue("codeLocations", frames);
        if (jobId != null) log.addKeyValue("jobId", jobId.toString());
        log.log("Operation failed");
    }
}
