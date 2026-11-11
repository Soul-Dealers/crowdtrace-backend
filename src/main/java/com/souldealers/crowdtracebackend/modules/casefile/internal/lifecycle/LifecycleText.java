package com.souldealers.crowdtracebackend.modules.casefile.internal.lifecycle;

import com.souldealers.crowdtracebackend.shared.ValidationException;

/** Normalizes and bounds required user-provided lifecycle text. */
final class LifecycleText {
    static final int MAX_LENGTH = 2000;

    private LifecycleText() { }

    static String normalize(String value, String label) {
        String stripped = value == null ? null : stripUnicodeSpace(value);
        if (stripped == null || stripped.isEmpty()) {
            throw new ValidationException(label + " is required");
        }
        if (stripped.length() > MAX_LENGTH) {
            throw new ValidationException(label + " must be at most " + MAX_LENGTH + " characters");
        }
        return stripped;
    }

    private static String stripUnicodeSpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isSpace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isSpace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
