package com.souldealers.crowdtracebackend.modules.notification;

public enum NotificationType {
    /** A top-level comment on the recipient's own case. */
    NEW_COMMENT,
    /** A reply in a thread the recipient wrote in. */
    COMMENT_REPLY,
    CASE_STATUS_CHANGED
}
