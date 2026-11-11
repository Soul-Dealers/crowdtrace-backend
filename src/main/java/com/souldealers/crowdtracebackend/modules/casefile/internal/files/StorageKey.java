package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;

import java.util.UUID;
import java.util.regex.Pattern;

/** Server-generated object key. Never built from client input; toString is redacted. */
public record StorageKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("^(reports|photos)/[0-9a-f-]{36}$");
    private static final int MAX_LENGTH = 512;

    public StorageKey {
        if (value == null || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
    }

    public static StorageKey generate(CaseFilePurpose purpose) {
        return new StorageKey(purpose.storagePrefix() + "/" + UUID.randomUUID());
    }

    public static StorageKey parse(String value, CaseFilePurpose expected) {
        StorageKey key = new StorageKey(value);
        if (!value.startsWith(expected.storagePrefix() + "/")) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return key;
    }

    @Override
    public String toString() {
        return "StorageKey[redacted]";
    }
}
