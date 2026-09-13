package com.souldealers.crowdtracebackend.modules.identity.internal.repository;

import com.souldealers.crowdtracebackend.modules.identity.VerificationStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.VerificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    Page<VerificationRequest> findByStatusOrderByCreatedAtAsc(
            VerificationStatus status,
            Pageable pageable);

    Page<VerificationRequest> findByUserIdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable);

    default Page<VerificationRequest> findPendingRequests(Pageable pageable) {
        return findByStatusOrderByCreatedAtAsc(VerificationStatus.PENDING, pageable);
    }
}
