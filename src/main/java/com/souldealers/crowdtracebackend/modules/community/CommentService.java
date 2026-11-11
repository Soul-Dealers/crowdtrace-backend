package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

public interface CommentService {

    /** Posts to an approved case's thread; throws NotFoundException for any other case. */
    CommentResponse create(Long caseId, Long authorId, CreateCommentRequest request);

    /** Visible comments on an approved case, newest first; throws NotFoundException for any other case. */
    CursorResponse<CommentResponse> listPublic(Long caseId, String cursor, int size);
}
