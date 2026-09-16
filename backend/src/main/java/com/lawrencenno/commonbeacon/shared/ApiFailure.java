package com.lawrencenno.commonbeacon.shared;

/** A controlled application failure safe to return to a client. */
public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;

    public ApiFailure(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
