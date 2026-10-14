package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Uses persist exclusively; merge would create an update path for immutable rows. */
@Repository
class CaseReviewWriterImpl implements CaseReviewWriter {

    private final EntityManager entityManager;

    CaseReviewWriterImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public CaseReview saveAndFlush(CaseReview review) {
        if (review.id() != null) {
            throw new IllegalArgumentException("Cannot append a case review that already has an id");
        }
        entityManager.persist(review);
        entityManager.flush();
        return review;
    }
}
