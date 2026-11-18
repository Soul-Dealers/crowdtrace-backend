package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Comment reads and writes for reporting and moderation. Every mutation joins the caller's transaction and fails
 * without one, so the caller's report rows, comment changes and audit events commit together.
 */
public interface CommentModerationService {
    /**
     * Takes the case's shared lock, then locks the comment.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException the comment is missing, removed or deleted
     *         by its author, or its case is not public
     */
    ModeratedComment lockReportable(long commentId);

    /**
     * Locks the comment in any state.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException the comment is missing
     */
    ModeratedComment lockForDecision(long commentId);

    void addOpenReport(long commentId);

    /** Marks the comment removed at {@code at} and zeroes its open-report count. */
    void remove(long commentId, LocalDateTime at);

    void clearOpenReports(long commentId);

    /** Comments by id; unknown ids are left out. */
    @RequiresModerator
    Map<Long, CommentModerationView> getModerationViews(Collection<Long> commentIds);
}
