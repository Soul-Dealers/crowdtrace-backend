package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;

/** Ids only: the client loads the case or comment itself, so nothing private rides along. */
public record InAppNotificationResponse(Long id, NotificationType type, Long caseId, Long commentId,
                                        NotifiedCaseStatus caseStatus, boolean read, LocalDateTime createdAt) {}
