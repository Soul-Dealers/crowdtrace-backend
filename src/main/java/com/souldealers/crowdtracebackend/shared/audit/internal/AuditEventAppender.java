package com.souldealers.crowdtracebackend.shared.audit.internal;

/** Insert-only persistence fragment for audit events. */
interface AuditEventAppender {
    AuditEvent saveAndFlush(AuditEvent event);
}
