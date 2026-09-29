package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    Page<VerificationRequest> findByStatusOrderByCreatedAtAsc(
            VerificationStatus status,
            Pageable pageable);

    Page<VerificationRequest> findByUserIdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable);

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
