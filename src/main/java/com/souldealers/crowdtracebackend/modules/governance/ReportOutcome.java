package com.souldealers.crowdtracebackend.modules.governance;

/** @param created false when the caller already had an open report on the comment */
public record ReportOutcome(CommentReportResponse response, boolean created) {
}
