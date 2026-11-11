package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.governance.internal.ReviewNotes;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Approval of a pending case. Notes are optional and admin-only; blank notes are stored as none. */
public record ApproveCaseRequest(
        @NotNull @PositiveOrZero Long expectedCaseVersion,
        @Size(max = ReviewNotes.MAX_LENGTH) String reviewNotes) {
}
