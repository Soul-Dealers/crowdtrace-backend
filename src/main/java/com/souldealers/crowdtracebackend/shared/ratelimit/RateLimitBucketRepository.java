package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The entire rate-limit mechanism, expressed as one atomic statement.
 *
 * <p>Not a JPA entity by design. Counting correctly under concurrency requires
 * the read and the write to be one statement; an entity would invite
 * find-then-save, which loses updates the moment two replicas race.
 */
@Repository
@RequiredArgsConstructor
public class RateLimitBucketRepository {

    private final EntityManager entityManager;

    /** A bucket's state after a charge. */
    public record BucketState(int requestCount, Instant windowEndsAt) {}

    private static final String CHARGE = """
            INSERT INTO rate_limit_bucket AS b
                   (scope, action, subject_key, request_count,
                    window_started_at, window_ends_at, purge_after)
            VALUES (:scope, :action, :subjectKey, 1,
                    now(),
                    now() + make_interval(secs => :windowSeconds),
                    now() + make_interval(secs => :windowSeconds) + interval '1 hour')
            ON CONFLICT (scope, action, subject_key) DO UPDATE SET
                request_count = CASE WHEN b.window_ends_at <= now()
                                     THEN 1
                                     ELSE b.request_count + 1 END,
                window_started_at = CASE WHEN b.window_ends_at <= now()
                                         THEN now()
                                         ELSE b.window_started_at END,
                window_ends_at = CASE WHEN b.window_ends_at <= now()
                                      THEN now() + make_interval(secs => :windowSeconds)
                                      ELSE b.window_ends_at END,
                purge_after = CASE WHEN b.window_ends_at <= now()
                                   THEN now() + make_interval(secs => :windowSeconds)
                                        + interval '1 hour'
                                   ELSE b.purge_after END
            RETURNING request_count, window_ends_at
            """;

    /**
     * Increments the bucket and returns its new state.
     *
     * <p>A denied request still increments, but deliberately does NOT extend
     * {@code window_ends_at} — see the ELSE branches. Extending on denial would let
     * an attacker hold a victim in permanent lockout by continuing to hammer a
     * blocked bucket.
     */
    @Transactional
    public BucketState charge(String scope, String action, byte[] subjectKey, long windowSeconds) {
        Query query = entityManager.createNativeQuery(CHARGE)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .setParameter("windowSeconds", (double) windowSeconds);

        Object[] row = (Object[]) query.getSingleResult();
        return toState(row);
    }

    /** Reads the bucket without charging it. Used to test a lockout without consuming quota. */
    @Transactional(readOnly = true)
    public Optional<BucketState> peek(String scope, String action, byte[] subjectKey) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT request_count, window_ends_at
                        FROM rate_limit_bucket
                        WHERE scope = :scope AND action = :action AND subject_key = :subjectKey
                        """)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(toState(rows.getFirst()));
    }

    /** Clears a bucket. Used to refund a successful login or OTP verification. */
    @Transactional
    public void reset(String scope, String action, byte[] subjectKey) {
        entityManager.createNativeQuery("""
                        DELETE FROM rate_limit_bucket
                        WHERE scope = :scope AND action = :action AND subject_key = :subjectKey
                        """)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .executeUpdate();
    }

    @Transactional
    public int purgeExpired() {
        return entityManager.createNativeQuery(
                        "DELETE FROM rate_limit_bucket WHERE purge_after < now()")
                .executeUpdate();
    }

    private BucketState toState(Object[] row) {
        int count = ((Number) row[0]).intValue();
        return new BucketState(count, toInstant(row[1]));
    }

    /**
     * Native queries hand back whatever the driver and Hibernate agree on for
     * {@code timestamptz}, and that mapping has changed across Hibernate versions
     * (Instant today, java.sql.Timestamp historically). Accept the known shapes
     * rather than pinning one, so a dependency bump cannot silently 500 every
     * rate-limited endpoint again.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type from rate_limit_bucket: "
                            + (value == null ? "null" : value.getClass().getName()));
        };
    }
}
