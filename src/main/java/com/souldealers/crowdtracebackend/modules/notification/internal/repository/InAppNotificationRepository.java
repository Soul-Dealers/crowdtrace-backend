package com.souldealers.crowdtracebackend.modules.notification.internal.repository;

import com.souldealers.crowdtracebackend.modules.notification.internal.model.InAppNotification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InAppNotificationRepository extends Repository<InAppNotification, Long> {

    /** A redelivered event leaves the first row alone. */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, case_id, type, comment_id, case_status, event_id, created_at)"
            + " VALUES (:recipientId, :caseId, :type, :commentId, :caseStatus, :eventId, :now)"
            + " ON CONFLICT (event_id, recipient_id) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("eventId") String eventId, @Param("recipientId") long recipientId,
                        @Param("caseId") long caseId, @Param("type") String type,
                        @Param("commentId") Long commentId, @Param("caseStatus") String caseStatus,
                        @Param("now") LocalDateTime now);

    @Query("SELECT n FROM InAppNotification n WHERE n.recipientId = :recipientId ORDER BY n.createdAt DESC, n.id DESC")
    List<InAppNotification> findFirst(@Param("recipientId") long recipientId, Limit limit);

    @Query("SELECT n FROM InAppNotification n WHERE n.recipientId = :recipientId"
            + " AND (n.createdAt < :createdAt OR (n.createdAt = :createdAt AND n.id < :id))"
            + " ORDER BY n.createdAt DESC, n.id DESC")
    List<InAppNotification> findAfter(@Param("recipientId") long recipientId,
                                      @Param("createdAt") LocalDateTime createdAt, @Param("id") long id, Limit limit);

    @Query("SELECT COUNT(n) FROM InAppNotification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    long countUnread(@Param("recipientId") long recipientId);

    boolean existsByIdAndRecipientId(Long id, long recipientId);

    @Modifying
    @Query("UPDATE InAppNotification n SET n.readAt = :now"
            + " WHERE n.id = :id AND n.recipientId = :recipientId AND n.readAt IS NULL")
    int markRead(@Param("id") long id, @Param("recipientId") long recipientId, @Param("now") LocalDateTime now);
}
