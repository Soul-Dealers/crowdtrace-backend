package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

public interface CommentService {

    /**
     * Posts to an approved case's thread, as a reply when parentCommentId is set. Throws NotFoundException for any
     * other case, or a parent that is missing, on another case, removed or deleted by its author.
     */
    CommentResponse create(Long caseId, Long authorId, CreateCommentRequest request);

    /**
     * One page of an approved case's top-level comments, or of one comment's direct replies when parentCommentId is
     * set, newest first. Throws NotFoundException for any other case, or a parent that is missing or removed.
     */
    CursorResponse<CommentResponse> listPublic(Long caseId, Long parentCommentId, String cursor, int size);

    /** Author-only soft deletion that erases the text. Repeat calls are harmless; anyone else gets NotFoundException. */
    void deleteByAuthor(Long caseId, Long commentId, Long authorId);
}
