package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CaseRecordRepository extends JpaRepository<CaseRecord, Long> {
    Optional<CaseRecord> findByIdAndReviewStatus(Long id, ReviewStatus reviewStatus);

    Page<CaseRecord> findByReviewStatusOrderByApprovedAtDescIdDesc(ReviewStatus reviewStatus, Pageable pageable);

    Optional<CaseRecord> findByIdAndReporterId(Long id, Long reporterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CaseRecord c WHERE c.id = :id AND c.reporterId = :reporterId")
    Optional<CaseRecord> findOwnedByIdForUpdate(@Param("id") Long id, @Param("reporterId") Long reporterId);

    Page<CaseRecord> findByReporterIdOrderByCreatedAtDescIdDesc(Long reporterId, Pageable pageable);

    Page<CaseRecord> findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAscIdAsc(
            Collection<ReviewStatus> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CaseRecord c WHERE c.id = :id")
    Optional<CaseRecord> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT c.id AS id, c.fullName AS fullName, c.lastSeenDate AS lastSeenDate
            FROM CaseRecord c
            WHERE c.id <> :caseId AND c.lastSeenDate BETWEEN :from AND :to
            ORDER BY c.id
            """)
    List<CaseDuplicateCandidate> findDuplicateCandidates(
            @Param("caseId") Long caseId, @Param("from") LocalDate from, @Param("to") LocalDate to);

}
