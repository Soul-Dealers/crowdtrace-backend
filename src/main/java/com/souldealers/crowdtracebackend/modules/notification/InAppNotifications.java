package com.souldealers.crowdtracebackend.modules.notification;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

import java.util.Collection;

/**
 * In-app notifications. The notify methods run in their own transaction so after-commit listeners can call them;
 * an event reaches each recipient at most once. userId must come from the authenticated principal.
 */
public interface InAppNotifications {

    /** Tells the case's reporter about a top-level comment on their case. */
    void notifyNewComment(String eventId, long recipientId, long caseId, long commentId);

    /** Tells each recipient that commentId replied into a thread they wrote in. */
    void notifyReply(String eventId, Collection<Long> recipientIds, long caseId, long commentId);

    void notifyStatusChanged(String eventId, Collection<Long> recipientIds, long caseId, NotifiedCaseStatus status);

    /** The user's notifications, newest first. */
    CursorResponse<InAppNotificationResponse> listOwn(Long userId, String cursor, int size);

    long countUnread(Long userId);

    /** Marks the user's own notification read; repeat calls are harmless. Throws NotFoundException otherwise. */
    void markRead(Long notificationId, Long userId);
}
