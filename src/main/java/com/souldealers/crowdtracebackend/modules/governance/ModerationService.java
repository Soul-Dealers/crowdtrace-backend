package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface ModerationService {
    /** Comments with open reports, oldest open report first, then comment id. Page size at most 50. */
    @RequiresModerator
    PagedResponse<CommentModerationQueueItem> listQueue(Pageable pageable);

    /** @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown comment */
    @RequiresModerator
    CommentModerationDetail getDetail(Long commentId);

    /**
     * Removes a reported comment and resolves every open report on it as REMOVED. actorEmail must come from the
     * authenticated principal.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown comment
     * @throws org.springframework.security.access.AccessDeniedException the moderator wrote the comment
     * @throws com.souldealers.crowdtracebackend.shared.ConflictException already removed, or no open reports
     */
    @RequiresModerator
    ModerationDecisionResponse remove(String actorEmail, Long commentId, RemoveCommentRequest request);

    /**
     * Keeps a reported comment and resolves every open report on it as DISMISSED.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown comment
     * @throws org.springframework.security.access.AccessDeniedException the moderator wrote the comment
     * @throws com.souldealers.crowdtracebackend.shared.ConflictException no open reports
     */
    @RequiresModerator
    ModerationDecisionResponse keep(String actorEmail, Long commentId, KeepCommentRequest request);
}
