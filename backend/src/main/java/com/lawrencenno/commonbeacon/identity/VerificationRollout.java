package com.lawrencenno.commonbeacon.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only legacy-access transition mode is supported until authoritative guards are implemented. */
@Component
public class VerificationRollout {
    public enum Mode { TRANSITION }
    private final Mode mode;
    public VerificationRollout(@Value("${commonbeacon.identity.verification-mode}") Mode mode) {
        this.mode = mode;
    }
    public Mode mode() { return mode; }
}
