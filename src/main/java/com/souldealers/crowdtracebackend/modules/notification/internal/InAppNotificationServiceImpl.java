package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.notification.InAppNotificationResponse;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import com.souldealers.crowdtracebackend.modules.notification.NotificationType;
import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import com.souldealers.crowdtracebackend.modules.notification.internal.model.InAppNotification;
import com.souldealers.crowdtracebackend.modules.notification.internal.repository.InAppNotificationRepository;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.SeekCursor;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.NOTIFICATION_NOT_FOUND;

@Service
@RequiredArgsConstructor
class InAppNotificationServiceImpl implements InAppNotifications {
    static final int MAX_PAGE_SIZE = 50;

    private final InAppNotificationRepository notifications;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyNewComment(String eventId, Collection<Long> recipientIds, long caseId, long commentId) {
        LocalDateTime now = now();
        recipientIds.forEach(recipient -> notifications.insertIfAbsent(eventId, recipient, caseId,
                NotificationType.NEW_COMMENT.name(), commentId, null, now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyStatusChanged(String eventId, Collection<Long> recipientIds, long caseId,
                                    NotifiedCaseStatus status) {
        LocalDateTime now = now();
        recipientIds.forEach(recipient -> notifications.insertIfAbsent(eventId, recipient, caseId,
                NotificationType.CASE_STATUS_CHANGED.name(), null, status.name(), now));
    }

    @Override
    @Transactional(readOnly = true)
    public CursorResponse<InAppNotificationResponse> listOwn(Long userId, String token, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        SeekCursor cursor = token == null ? null : SeekCursor.decode(token);
        Limit limit = Limit.of(size + 1);
        List<InAppNotification> fetched = cursor == null ? notifications.findFirst(userId, limit)
                : notifications.findAfter(userId, cursor.createdAt(), cursor.id(), limit);
        boolean hasNext = fetched.size() > size;
        List<InAppNotification> page = hasNext ? fetched.subList(0, size) : fetched;
        List<InAppNotificationResponse> content = page.stream().map(InAppNotificationServiceImpl::toResponse).toList();
        String next = hasNext ? new SeekCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new CursorResponse<>(content, size, hasNext, next);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(Long userId) {
        return notifications.countUnread(userId);
    }

    @Override
    @Transactional
    public void markRead(Long notificationId, Long userId) {
        if (notifications.markRead(notificationId, userId, now()) == 0
                && !notifications.existsByIdAndRecipientId(notificationId, userId)) {
            throw new NotFoundException(NOTIFICATION_NOT_FOUND);
        }
    }

    /** Microseconds match Postgres precision, so a cursor built from a saved row stays valid. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static InAppNotificationResponse toResponse(InAppNotification n) {
        return new InAppNotificationResponse(n.getId(), n.getType(), n.getCaseId(), n.getCommentId(),
                n.getCaseStatus(), n.getReadAt() != null, n.getCreatedAt());
    }
}
