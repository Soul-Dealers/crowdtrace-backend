package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;

import java.io.IOException;
import java.nio.file.Path;

public interface FileStorage {
    /** Returning normally means the object has been fully stored. */
    void put(StoredObject metadata, Path content) throws IOException;

    void delete(StorageKey key) throws IOException;
}
