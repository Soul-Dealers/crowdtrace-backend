package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.governance.internal.ReviewNotes;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Rejection of a pending case. Notes are required, admin-only and must not be blank after a Unicode trim. */
public record RejectCaseRequest(
        @NotNull @PositiveOrZero Long expectedCaseVersion,
        @NotNull @Pattern(regexp = ReviewNotes.NOT_BLANK, message = "must not be blank")
        @Size(max = ReviewNotes.MAX_LENGTH) String reviewNotes) {
}
