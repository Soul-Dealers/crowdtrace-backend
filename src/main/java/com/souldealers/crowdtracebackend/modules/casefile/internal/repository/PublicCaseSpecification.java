package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseCursor;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Public search always constrains approval, then adds only the supplied filters. */
public final class PublicCaseSpecification {
    public static final Sort ORDER = Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id"));

    private PublicCaseSpecification() {}

    public static Specification<CaseRecord> matching(PublicCaseSearchQuery query) {
        return matching(query, null);
    }

    public static Specification<CaseRecord> matching(PublicCaseSearchQuery query, PublicCaseCursor cursor) {
        PublicCaseSearchQuery q = query == null ? PublicCaseSearchQuery.all() : query;
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("reviewStatus"), ReviewStatus.APPROVED));
            predicates.add(root.get("caseStatus").in(q.statuses()));
            if (q.nameLikePattern() != null) {
                predicates.add(cb.like(cb.lower(root.get("fullName")), q.nameLikePattern(), '\\'));
            }
            if (q.region() != null) {
                predicates.add(cb.equal(root.get("region"), q.region()));
            }
            if (q.gender() != null) {
                predicates.add(cb.equal(root.get("gender"), q.gender()));
            }
            if (q.ageMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("age"), q.ageMin()));
            }
            if (q.ageMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("age"), q.ageMax()));
            }
            if (q.lastSeenFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastSeenDate"), q.lastSeenFrom()));
            }
            if (q.lastSeenTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastSeenDate"), q.lastSeenTo()));
            }
            if (q.approvedFromInclusive() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("approvedAt"), q.approvedFromInclusive()));
            }
            if (q.approvedToExclusive() != null) {
                predicates.add(cb.lessThan(root.get("approvedAt"), q.approvedToExclusive()));
            }
            if (cursor != null) {
                predicates.add(seekAfter(root.get("approvedAt"), root.get("id"), cb, cursor));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate seekAfter(Expression<LocalDateTime> approvedAt, Expression<Long> id,
                                       CriteriaBuilder criteriaBuilder, PublicCaseCursor cursor) {
        if (cursor.approvedAt() == null) {
            return criteriaBuilder.or(criteriaBuilder.isNotNull(approvedAt), criteriaBuilder.and(
                    criteriaBuilder.isNull(approvedAt), criteriaBuilder.lessThan(id, cursor.id())));
        }
        HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) criteriaBuilder;
        return hcb.isTrue(hcb.sql("(?, ?) < (?, ?)", Boolean.class,
                approvedAt, id, hcb.value(cursor.approvedAt()), hcb.value(cursor.id())));
    }
}
