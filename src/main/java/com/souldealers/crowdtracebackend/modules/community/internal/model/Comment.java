package com.souldealers.crowdtracebackend.modules.community.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "comments")
public class Comment {
    public static final String DELETED_BODY = "[deleted]";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private long authorId;

    @Column(name = "parent_comment_id", updatable = false)
    private Long parentCommentId;

    /** Never edited. Only CommentRepository.scrubByAuthor overwrites it, with {@link #DELETED_BODY}. */
    @Column(name = "body", nullable = false, length = 2000, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 32)
    private ModerationStatus moderationStatus;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "deleted_at", updatable = false)
    private LocalDateTime deletedAt;

    protected Comment() { }

    public static Comment post(long caseId, Long parentCommentId, long authorId, String body,
                               LocalDateTime createdAt) {
        Comment comment = new Comment();
        comment.caseId = caseId;
        comment.parentCommentId = parentCommentId;
        comment.authorId = authorId;
        comment.body = body;
        comment.moderationStatus = ModerationStatus.VISIBLE;
        comment.createdAt = createdAt;
        return comment;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
