package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;

/** Normalizes and bounds required user-provided lifecycle text. */
public final class LifecycleText {
    public static final int MAX_LENGTH = 2000;

    private LifecycleText() { }

    public static String normalize(String value, String label) {
        String stripped = value == null ? null : value.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new ValidationException(label + " is required");
        }
        if (stripped.length() > MAX_LENGTH) {
            throw new ValidationException(label + " must be at most " + MAX_LENGTH + " characters");
        }
        return stripped;
    }
}
