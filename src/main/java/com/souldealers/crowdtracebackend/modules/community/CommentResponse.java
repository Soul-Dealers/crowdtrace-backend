package com.souldealers.crowdtracebackend.modules.community;

import com.souldealers.crowdtracebackend.modules.identity.PublicUserResponse;

import java.time.LocalDateTime;

/** A visible comment in a case's public thread. The author is pseudonymous: no email or account id. */
public record CommentResponse(Long id, PublicUserResponse author, String body, LocalDateTime createdAt) {
}
