package com.souldealers.crowdtracebackend.modules.governance;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** @param details trimmed; required when reason is OTHER */
public record ReportCommentRequest(@NotNull ReportReason reason, @Size(max = 1000) String details) {
}
