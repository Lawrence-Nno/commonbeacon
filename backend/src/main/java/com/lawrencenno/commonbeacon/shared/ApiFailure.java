package com.lawrencenno.commonbeacon.shared;

import java.util.Map;

/** A controlled application failure safe to return to a client. */
public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiFailure(int status, String code, String detail) {
        this(status, code, detail, Map.of());
    }

    public ApiFailure(int status, String code, String detail, Map<String, String> fieldErrors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
