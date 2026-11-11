package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.shared.ValidationException;

/** Review notes rules (D8), shared by the request DTOs and the service. */
public final class ReviewNotes {
    public static final int MAX_LENGTH = 2000;
    /** At least one character that {@link String#strip()} would keep. */
    public static final String NOT_BLANK = "(?s).*\\P{javaWhitespace}.*";

    private ReviewNotes() { }

    /** Approval notes: stripped, blank becomes {@code null}. */
    public static String optional(String notes) {
        String stripped = notes == null ? null : notes.strip();
        if (stripped == null || stripped.isEmpty()) {
            return null;
        }
        return capped(stripped);
    }

    /** Rejection notes: stripped and required. */
    public static String required(String notes) {
        String stripped = optional(notes);
        if (stripped == null) {
            throw new ValidationException("Review notes are required to reject a case");
        }
        return stripped;
    }

    private static String capped(String notes) {
        if (notes.length() > MAX_LENGTH) {
            throw new ValidationException("Review notes must be at most " + MAX_LENGTH + " characters");
        }
        return notes;
    }
}
