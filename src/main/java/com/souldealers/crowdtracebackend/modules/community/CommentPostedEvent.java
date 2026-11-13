package com.souldealers.crowdtracebackend.modules.community;

/** Id-only event published inside the posting transaction; never carries the body or author identity. */
public record CommentPostedEvent(String eventId, long caseId, long commentId, long authorId) {

    public static CommentPostedEvent of(long caseId, long commentId, long authorId) {
        return new CommentPostedEvent("comment:" + commentId, caseId, commentId, authorId);
    }
}
