package com.souldealers.crowdtracebackend.modules.governance;

public interface CommentReportService {
    /**
     * Reports a visible comment on a public case. A second report by the same user while the first is open returns
     * the first unchanged. reporterId must come from the authenticated principal.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException the comment is missing, removed or deleted,
     *         or its case is not public
     * @throws org.springframework.security.access.AccessDeniedException the caller wrote the comment
     * @throws com.souldealers.crowdtracebackend.shared.ValidationException OTHER without details
     */
    ReportOutcome report(Long commentId, Long reporterId, ReportCommentRequest request);
}
