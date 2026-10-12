package com.souldealers.crowdtracebackend.modules.governance.internal.model;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** One human review decision. Append-only (V17): a changed decision is a new row. */
@Entity
@Immutable
@Table(name = "case_reviews")
public class CaseReview {

    private static final String HUMAN = "HUMAN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32, updatable = false)
    private ReviewStatus decision;

    @Column(name = "review_source", nullable = false, length = 32, updatable = false)
    private String source;

    @Column(name = "review_notes", length = 2000, updatable = false)
    private String reviewNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CaseReview() { }

    public static CaseReview create(long caseId, long reviewerId, ReviewStatus decision,
            String reviewNotes, LocalDateTime createdAt) {
        if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) {
            throw new IllegalArgumentException("A review decision must be APPROVED or REJECTED");
        }
        var review = new CaseReview();
        review.caseId = caseId;
        review.reviewerId = reviewerId;
        review.decision = decision;
        review.source = HUMAN;
        review.reviewNotes = reviewNotes;
        review.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        return review;
    }

    public Long id() { return id; }
    public long caseId() { return caseId; }
    public long reviewerId() { return reviewerId; }
    public ReviewStatus decision() { return decision; }
    public String source() { return source; }
    public String reviewNotes() { return reviewNotes; }
    public LocalDateTime createdAt() { return createdAt; }
}
