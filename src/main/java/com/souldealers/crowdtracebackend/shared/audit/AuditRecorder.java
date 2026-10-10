package com.souldealers.crowdtracebackend.shared.audit;

/** Records an immutable audit event in the caller's transaction. */
public interface AuditRecorder {

    String CORRELATION_ID_PATTERN = "^[A-Za-z0-9._-]{1,64}$";

    /** Records an event and returns its generated database id. */
    long record(AuditActor actor, AuditAction action, long targetId, AuditMetadata metadata);
}
