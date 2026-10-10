package com.souldealers.crowdtracebackend.shared.audit;

/** Closed reasons for appending an audit correction event. */
public enum AuditCorrectionReason {
    RECORDED_IN_ERROR,
    WRONG_ACTOR,
    WRONG_TARGET,
    WRONG_METADATA
}
