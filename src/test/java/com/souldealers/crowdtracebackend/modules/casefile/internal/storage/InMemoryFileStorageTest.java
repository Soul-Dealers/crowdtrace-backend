package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryFileStorageTest {
    @TempDir
    Path directory;

    private final InMemoryFileStorage storage = new InMemoryFileStorage();

    @Test
    void storesIndependentBytesAndTheirMetadata() throws IOException {
        StoredObject object = report();
        byte[] bytes = {1, 2, 3};
        Path path = Files.write(directory.resolve("content"), bytes);

        storage.put(object, path);
        Files.delete(path);

        Map<?, ?> objects = (Map<?, ?>) ReflectionTestUtils.getField(storage, "objects");
        assertThat(objects).hasSize(1);
        Object stored = objects.get(object.key());
        assertThat(ReflectionTestUtils.getField(stored, "metadata")).isEqualTo(object);
        assertThat((byte[]) ReflectionTestUtils.getField(stored, "bytes")).isEqualTo(bytes);
    }

    @Test
    void putThenDeleteAllowsTheKeyToBeStoredAgain() throws IOException {
        StoredObject object = report();
        Path path = Files.write(directory.resolve("content"), new byte[]{1, 2, 3});

        storage.put(object, path);
        storage.delete(object.key());
        storage.put(object, path);
        storage.delete(object.key());
        storage.delete(object.key());
    }

    @Test
    void rejectsADuplicateKeyRatherThanOverwriting() throws IOException {
        StoredObject object = report();
        Path path = Files.write(directory.resolve("content"), new byte[]{1, 2, 3});
        storage.put(object, path);

        assertThatThrownBy(() -> storage.put(object, path)).isInstanceOf(IllegalStateException.class)
                .hasMessage("An object already exists for this storage key")
                .hasMessageNotContaining(object.key().value());
    }

    @Test
    void metadataToStringRedactsTheKeyAndChecksum() {
        StoredObject object = report();

        assertThat(object.toString()).doesNotContain(object.key().value(), object.checksumSha256());
    }

    private StoredObject report() {
        return new StoredObject(StorageKey.generate(CaseFilePurpose.REPORT), FileVisibility.PRIVATE,
                "application/pdf", 3, "a".repeat(64));
    }
}
