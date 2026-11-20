package com.souldealers.crowdtracebackend.modules.governance;

import java.util.List;

/**
 * A moderator removed a reported comment. Published inside the removal's transaction; listeners act after commit.
 *
 * @param eventId     stable per comment: a comment is removed at most once
 * @param reporterIds distinct users whose open reports the removal resolved
 */
public record CommentRemovedEvent(String eventId, long caseId, long commentId, List<Long> reporterIds) {
    public static CommentRemovedEvent of(long caseId, long commentId, List<Long> reporterIds) {
        return new CommentRemovedEvent("comment-removed:" + commentId, caseId, commentId, List.copyOf(reporterIds));
    }
}
