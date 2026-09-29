package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    @EntityGraph(attributePaths = "user")
    Page<VerificationRequest> findByStatusOrderByCreatedAtAsc(
            VerificationStatus status,
            Pageable pageable);

    Page<VerificationRequest> findByUserIdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable);

    List<VerificationRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "user")
    java.util.Optional<VerificationRequest> findWithUserById(Long id);

    boolean existsByUserIdAndVerificationTypeAndStatus(
            Long userId,
            VerificationType verificationType,
            VerificationStatus status);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE VerificationRequest request
               SET request.status = :toStatus,
                   request.reviewer = :reviewer,
                   request.reviewNotes = :reviewNotes,
                   request.reviewedAt = :reviewedAt
             WHERE request.id = :id
               AND request.status = :fromStatus
            """)
    int applyDecision(
            @Param("id") Long id,
            @Param("fromStatus") VerificationStatus fromStatus,
            @Param("toStatus") VerificationStatus toStatus,
            @Param("reviewer") User reviewer,
            @Param("reviewNotes") String reviewNotes,
            @Param("reviewedAt") LocalDateTime reviewedAt);

    /**
     * Decided requests for one user, newest decision first.
     *
     * <p>Ordered on the review timestamp, falling back to creation so the ordering is
     * deterministic on both PostgreSQL and the H2 test database — their default NULL
     * ordering differs, and a rank that depends on the engine would make this pass in
     * tests and drift in production. The id breaks exact ties.
     */
    @Query("""
            SELECT request FROM VerificationRequest request
            WHERE request.user.id = :userId
              AND request.status <> :excludedStatus
            ORDER BY COALESCE(request.reviewedAt, request.createdAt) DESC, request.id DESC
            """)
    List<VerificationRequest> findRequestsByUserExcludingStatus(
            @Param("userId") Long userId,
            @Param("excludedStatus") VerificationStatus excludedStatus);

    default List<VerificationRequest> findDecidedRequestsNewestFirst(Long userId) {
        return findRequestsByUserExcludingStatus(userId, VerificationStatus.PENDING);
    }

    default Page<VerificationRequest> findPendingRequests(Pageable pageable) {
        return findByStatusOrderByCreatedAtAsc(VerificationStatus.PENDING, pageable);
    }
}
