package com.souldealers.crowdtracebackend.modules.community.internal.repository;

import com.souldealers.crowdtracebackend.modules.community.internal.model.Comment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    String VISIBLE = "com.souldealers.crowdtracebackend.modules.community.internal.model.ModerationStatus.VISIBLE";

    @Query("SELECT c FROM Comment c WHERE c.caseId = :caseId AND c.parentCommentId IS NULL AND c.moderationStatus = "
            + VISIBLE + " ORDER BY c.createdAt DESC, c.id DESC")
    List<Comment> findFirstRoots(@Param("caseId") long caseId, Limit limit);

    @Query("SELECT c FROM Comment c WHERE c.caseId = :caseId AND c.parentCommentId IS NULL AND c.moderationStatus = "
            + VISIBLE + " AND (c.createdAt < :createdAt OR (c.createdAt = :createdAt AND c.id < :id))"
            + " ORDER BY c.createdAt DESC, c.id DESC")
    List<Comment> findRootsAfter(@Param("caseId") long caseId, @Param("createdAt") LocalDateTime createdAt,
                                 @Param("id") long id, Limit limit);

    @Query("SELECT c FROM Comment c WHERE c.parentCommentId = :parentId AND c.moderationStatus = " + VISIBLE
            + " ORDER BY c.createdAt DESC, c.id DESC")
    List<Comment> findFirstReplies(@Param("parentId") long parentId, Limit limit);

    @Query("SELECT c FROM Comment c WHERE c.parentCommentId = :parentId AND c.moderationStatus = " + VISIBLE
            + " AND (c.createdAt < :createdAt OR (c.createdAt = :createdAt AND c.id < :id))"
            + " ORDER BY c.createdAt DESC, c.id DESC")
    List<Comment> findRepliesAfter(@Param("parentId") long parentId, @Param("createdAt") LocalDateTime createdAt,
                                   @Param("id") long id, Limit limit);

    /** Visible direct replies per parent, author-deleted ones included: the same rows the reply pages return. */
    @Query("SELECT c.parentCommentId AS parentId, COUNT(c) AS replies FROM Comment c WHERE c.parentCommentId IN "
            + ":parentIds AND c.moderationStatus = " + VISIBLE + " GROUP BY c.parentCommentId")
    List<ReplyCount> countVisibleReplies(@Param("parentIds") Collection<Long> parentIds);

    @Query("SELECT c FROM Comment c WHERE c.id = :id AND c.caseId = :caseId AND c.moderationStatus = " + VISIBLE)
    Optional<Comment> findVisible(@Param("id") long id, @Param("caseId") long caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Comment c WHERE c.id = :id AND c.caseId = :caseId AND c.moderationStatus = " + VISIBLE)
    Optional<Comment> findVisibleForUpdate(@Param("id") long id, @Param("caseId") long caseId);

    /** Author deletion: erases the text and keeps the row so replies stay attached. A second call changes nothing. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Comment c SET c.body = '" + Comment.DELETED_BODY + "', c.deletedAt = :now"
            + " WHERE c.id = :id AND c.deletedAt IS NULL")
    void scrubByAuthor(@Param("id") long id, @Param("now") LocalDateTime now);

    interface ReplyCount {
        Long getParentId();

        long getReplies();
    }
}
