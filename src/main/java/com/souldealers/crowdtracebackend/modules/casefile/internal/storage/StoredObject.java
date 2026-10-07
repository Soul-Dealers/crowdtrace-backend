package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;

public record StoredObject(StorageKey key, FileVisibility visibility, String contentType,
                           long sizeBytes, String checksumSha256) {
    @Override
    public String toString() {
        return "StoredObject[visibility=" + visibility + ", contentType=" + contentType
                + ", sizeBytes=" + sizeBytes + ", key=redacted, checksumSha256=redacted]";
    }
}
