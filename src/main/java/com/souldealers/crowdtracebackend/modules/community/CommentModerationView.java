package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;

import java.time.LocalDateTime;

/**
 * A comment as a moderator sees it. The body is the stored text, kept after removal and already scrubbed if its
 * author deleted it.
 *
 * @param author null when the author deleted the comment
 */
public record CommentModerationView(long id, long caseId, PublicUserResponse author, long authorId, String body,
                                    boolean deleted, boolean removed, LocalDateTime createdAt) {
}
