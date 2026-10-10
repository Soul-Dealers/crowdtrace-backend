package com.souldealers.crowdtracebackend.shared.audit;

/** Case statuses recorded without depending on the casefile module. */
public enum AuditCaseStatus {
    MISSING,
    FOUND_SAFE,
    FOUND_DECEASED,
    CLOSED
}
