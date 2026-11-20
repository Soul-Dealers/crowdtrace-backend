package com.souldealers.crowdtracebackend.modules.governance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param notes why the comment breaks the rules; trimmed, 1–2000 characters, kept on every resolved report */
public record RemoveCommentRequest(@NotBlank @Size(max = 2000) String notes) {
}
