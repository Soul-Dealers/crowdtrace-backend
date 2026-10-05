package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CaseRecordRepository extends JpaRepository<CaseRecord, Long> {
    Optional<CaseRecord> findByIdAndReviewStatus(Long id, ReviewStatus reviewStatus);

    Page<CaseRecord> findByReviewStatusOrderByApprovedAtDescIdDesc(ReviewStatus reviewStatus, Pageable pageable);


    Optional<CaseRecord> findByIdAndReporterId(Long id, Long reporterId);

}
