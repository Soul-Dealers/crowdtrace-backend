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
}
