package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Whether the user has a request in any of the given statuses, whatever its type.
     * A user holds at most one badge, so callers pass PENDING and APPROVED to ask
     * "is this user's single slot taken".
     */
    boolean existsByUserIdAndStatusIn(Long userId, Collection<VerificationStatus> statuses);

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

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE VerificationRequest request
               SET request.status = com.souldealers.crowdtracebackend.modules.identity.VerificationStatus.REVOKED,
                   request.revokedBy = :revokedBy,
                   request.revocationNotes = :revocationNotes,
                   request.revokedAt = :revokedAt
             WHERE request.id = :id
               AND request.status = com.souldealers.crowdtracebackend.modules.identity.VerificationStatus.APPROVED
            """)
    int applyRevocation(
            @Param("id") Long id,
            @Param("revokedBy") User revokedBy,
            @Param("revocationNotes") String revocationNotes,
            @Param("revokedAt") LocalDateTime revokedAt);

    default Page<VerificationRequest> findPendingRequests(Pageable pageable) {
        return findByStatusOrderByCreatedAtAsc(VerificationStatus.PENDING, pageable);
    }
}
