package com.souldealers.crowdtracebackend.shared.audit;

/** Closed target vocabulary stored with an audit event. */
public enum AuditTargetType {
    VERIFICATION_REQUEST("VERIFICATION_REQUEST"),
    CASE("CASE"),
    CASE_FILE("CASE_FILE"),
    COMMENT("COMMENT"),
    CONTENT_REPORT("CONTENT_REPORT"),
    USER("USER"),
    AUDIT_EVENT("AUDIT_EVENT");

    private final String code;

    AuditTargetType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
