package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One reported comment in the moderation queue.
 *
 * @param bodyExcerpt first 200 characters; null when the author deleted the comment
 * @param author      null when the author deleted the comment
 * @param reasons     open reports per reason; reasons with none are left out
 */
public record CommentModerationQueueItem(long commentId, long caseId, String caseName, boolean casePublic,
                                         String bodyExcerpt, PublicUserResponse author, boolean authorDeleted,
                                         long openReports, Map<ReportReason, Long> reasons,
                                         LocalDateTime oldestOpenAt, LocalDateTime newestOpenAt) {
}
