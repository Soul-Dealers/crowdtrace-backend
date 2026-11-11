package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;

import java.time.LocalDateTime;

/**
 * A comment in a case's public thread. The author is pseudonymous: no email or account id. When the author deleted
 * it, author and body are null and deleted is true; its replies stay readable.
 *
 * @param parentCommentId null for a top-level comment
 * @param replyCount      visible direct replies, not all descendants
 */
public record CommentResponse(Long id, Long parentCommentId, PublicUserResponse author, String body,
                              LocalDateTime createdAt, long replyCount, boolean deleted) {
}
