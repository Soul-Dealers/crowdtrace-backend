package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Development fallback; content does not survive a process restart. */
@Slf4j
public class InMemoryFileStorage implements FileStorage {
    private final Map<StorageKey, Content> objects = new ConcurrentHashMap<>();

    public InMemoryFileStorage() {
        log.warn("In-memory file storage is active: objects are lost on restart; not for production");
    }

    @Override
    public void put(StoredObject metadata, Path content) throws IOException {
        Content object = new Content(metadata, Files.readAllBytes(content));
        if (objects.putIfAbsent(metadata.key(), object) != null) {
            throw new IllegalStateException("An object already exists for this storage key");
        }
    }

    @Override
    public void delete(StorageKey key) {
        objects.remove(key);
    }

    private record Content(StoredObject metadata, byte[] bytes) {
    }
}
