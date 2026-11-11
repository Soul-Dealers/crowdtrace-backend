package com.souldealers.crowdtracebackend.modules.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** @param parentCommentId the comment being replied to; null posts a top-level comment */
public record CreateCommentRequest(
        @NotBlank(message = "body is required")
        @Size(max = 2000, message = "body must be at most 2000 characters")
        String body,
        @Positive(message = "parentCommentId must be positive")
        Long parentCommentId) {
}
