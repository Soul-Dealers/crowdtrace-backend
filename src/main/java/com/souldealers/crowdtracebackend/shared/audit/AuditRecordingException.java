package com.souldealers.crowdtracebackend.shared.audit;

/** An audit event could not be persisted. */
public class AuditRecordingException extends RuntimeException {
    public AuditRecordingException(String message) {
        super(message);
    }

    public AuditRecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
