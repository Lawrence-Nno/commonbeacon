package com.lawrencenno.commonbeacon.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** TRANSITION retains legacy ACTIVE access; ENFORCED requires current-address proof. */
@Component
public class VerificationRollout {
    public enum Mode { TRANSITION, ENFORCED }
    private final Mode mode;
    public VerificationRollout(@Value("${commonbeacon.identity.verification-mode}") Mode mode) {
        this.mode = mode;
    }
    public Mode mode() { return mode; }
}
