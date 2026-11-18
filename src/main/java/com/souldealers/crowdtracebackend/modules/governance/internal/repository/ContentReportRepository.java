package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.governance.internal.model.ContentReport;
import com.souldealers.crowdtracebackend.modules.governance.ReportReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {
    @Query("SELECT r FROM ContentReport r WHERE r.commentId = :commentId AND r.reporterId = :reporterId"
            + " AND r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportStatus.OPEN")
    Optional<ContentReport> findOpenByCommentIdAndReporterId(@Param("commentId") long commentId,
                                                             @Param("reporterId") long reporterId);

    /** One row per comment with open reports, oldest open report first, then comment id. */
    @Query(value = """
            SELECT comment_id AS commentId, count(*) AS openReports, min(created_at) AS oldestOpenAt,
                   max(created_at) AS newestOpenAt
            FROM content_reports WHERE status = 'OPEN'
            GROUP BY comment_id ORDER BY min(created_at), comment_id
            """, countQuery = "SELECT count(DISTINCT comment_id) FROM content_reports WHERE status = 'OPEN'",
            nativeQuery = true)
    Page<OpenReportGroup> findOpenGroups(Pageable pageable);

    @Query("SELECT r.commentId AS commentId, r.reason AS reason, COUNT(r) AS reports FROM ContentReport r"
            + " WHERE r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportStatus.OPEN"
            + " AND r.commentId IN :commentIds GROUP BY r.commentId, r.reason")
    List<ReasonCount> countOpenByReason(@Param("commentIds") Collection<Long> commentIds);

    List<ContentReport> findByCommentIdOrderByCreatedAtAscIdAsc(long commentId);

    @Query("SELECT r FROM ContentReport r WHERE r.commentId = :commentId"
            + " AND r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportStatus.OPEN"
            + " ORDER BY r.id")
    List<ContentReport> findOpenByCommentIdOrderById(@Param("commentId") long commentId);

    /** The one change V17 allows: every open report on the comment becomes resolved, all fields at once. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ContentReport r SET r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model"
            + ".ReportStatus.RESOLVED, r.resolution = :resolution, r.moderatorId = :moderatorId,"
            + " r.resolutionNotes = :notes, r.resolvedAt = :at WHERE r.commentId = :commentId"
            + " AND r.status = com.souldealers.crowdtracebackend.modules.governance.internal.model.ReportStatus.OPEN")
    int resolveOpen(@Param("commentId") long commentId, @Param("resolution") ReportResolution resolution,
                    @Param("moderatorId") long moderatorId, @Param("notes") String notes,
                    @Param("at") LocalDateTime at);

    interface OpenReportGroup {
        Long getCommentId();

        long getOpenReports();

        LocalDateTime getOldestOpenAt();

        LocalDateTime getNewestOpenAt();
    }

    interface ReasonCount {
        Long getCommentId();

        ReportReason getReason();

        long getReports();
    }
}
