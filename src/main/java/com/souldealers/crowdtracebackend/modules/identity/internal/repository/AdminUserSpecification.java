package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.shared.SeekCursor;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Filters for the Super Admin user list, seeking past a (createdAt, id) cursor. */
public final class AdminUserSpecification {
    public static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private AdminUserSpecification() {}

    public static Specification<User> matching(UserRoles role, UserStatus status, String q, SeekCursor cursor) {
        String prefix = prefixPattern(q);
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("accountStatus"), status));
            }
            if (prefix != null) {
                predicates.add(cb.or(cb.like(cb.lower(root.get("email")), prefix, '\\'),
                        cb.like(cb.lower(root.get("displayName")), prefix, '\\')));
            }
            if (cursor != null) {
                HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
                predicates.add(hcb.isTrue(hcb.sql("(?, ?) < (?, ?)", Boolean.class, root.get("createdAt"),
                        root.get("id"), hcb.value(cursor.createdAt()), hcb.value(cursor.id()))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Lowercased prefix with LIKE wildcards escaped, so {@code %} and {@code _} match literally. */
    private static String prefixPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
