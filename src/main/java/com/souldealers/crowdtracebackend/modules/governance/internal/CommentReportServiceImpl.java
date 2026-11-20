package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.community.CommentModerationService;
import com.souldealers.crowdtracebackend.modules.community.ModeratedComment;
import com.souldealers.crowdtracebackend.modules.governance.CommentReportResponse;
import com.souldealers.crowdtracebackend.modules.governance.CommentReportService;
import com.souldealers.crowdtracebackend.modules.governance.ReportCommentRequest;
import com.souldealers.crowdtracebackend.modules.governance.ReportOutcome;
import com.souldealers.crowdtracebackend.modules.governance.ReportReason;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.ContentReport;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.ContentReportRepository;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
class CommentReportServiceImpl implements CommentReportService {
    private final CommentModerationService comments;
    private final ContentReportRepository reports;
    private final Clock clock;

    @Override
    @Transactional
    public ReportOutcome report(Long commentId, Long reporterId, ReportCommentRequest request) {
        String details = request.details() == null || request.details().isBlank() ? null : request.details().strip();
        if (request.reason() == ReportReason.OTHER && details == null) {
            throw new ValidationException("details are required when the reason is OTHER");
        }
        // Holds the comment row until commit, so a removal and duplicate reports by the same user wait their turn.
        ModeratedComment comment = comments.lockReportable(commentId);
        if (comment.authorId() == reporterId) {
            throw new AccessDeniedException("You cannot report your own comment");
        }
        var existing = reports.findOpenByCommentIdAndReporterId(commentId, reporterId);
        if (existing.isPresent()) {
            return new ReportOutcome(toResponse(existing.get()), false);
        }
        ContentReport saved = reports.save(ContentReport.open(commentId, reporterId, request.reason(), details,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)));
        comments.addOpenReport(commentId);
        return new ReportOutcome(toResponse(saved), true);
    }

    private static CommentReportResponse toResponse(ContentReport report) {
        return new CommentReportResponse(report.getId(), report.getStatus().name());
    }
}
