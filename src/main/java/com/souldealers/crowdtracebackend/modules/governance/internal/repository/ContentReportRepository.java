package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.governance.internal.model.ContentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {
    @Query("SELECT r FROM ContentReport r WHERE r.commentId = :commentId AND r.reporterId = :reporterId"
            + " AND r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportStatus.OPEN")
    Optional<ContentReport> findOpenByCommentIdAndReporterId(@Param("commentId") long commentId,
                                                             @Param("reporterId") long reporterId);
}
