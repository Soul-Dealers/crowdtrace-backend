package com.souldealers.crowdtracebackend.modules.governance;

import jakarta.validation.constraints.Size;

/** @param notes optional; trimmed, at most 2000 characters */
public record KeepCommentRequest(@Size(max = 2000) String notes) {
}
