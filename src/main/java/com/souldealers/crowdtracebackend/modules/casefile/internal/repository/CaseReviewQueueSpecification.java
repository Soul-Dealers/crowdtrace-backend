package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewQueueQuery;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * One predicate per set filter, so each filter combination is its own SQL shape. Status is always constrained,
 * which keeps every shape on {@code idx_cases_review_queue} (review_status, priority_minor DESC, submitted_at).
 */
public final class CaseReviewQueueSpecification {
    /** Minors first, then oldest submission; id breaks ties so pages never repeat or skip. */
    public static final Sort ORDER = Sort.by(Sort.Order.desc("priorityMinor"), Sort.Order.asc("submittedAt"),
            Sort.Order.asc("id"));

    private CaseReviewQueueSpecification() {}

    public static Specification<CaseRecord> matching(CaseReviewQueueQuery query) {
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("reviewStatus").in(query.statuses()));
            if (query.priorityMinor() != null) {
                predicates.add(cb.equal(root.get("priorityMinor"), query.priorityMinor()));
            }
            if (query.duplicateFlag() != null) {
                predicates.add(cb.equal(root.get("duplicateFlag"), query.duplicateFlag()));
            }
            if (query.submittedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), query.submittedFrom()));
            }
            if (query.submittedTo() != null) {
                predicates.add(cb.lessThan(root.get("submittedAt"), query.submittedTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
