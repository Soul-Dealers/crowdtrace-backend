package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;

import java.util.List;
import java.util.Optional;

public final class CaseFileBoundaryFixtures {
    private CaseFileBoundaryFixtures() {}

    public static class GenericKeyExposure {
        private List<StorageKey> keys;

        public Optional<StorageKey> read() { return Optional.empty(); }
        public void write(List<StorageKey> input) {}
    }

    public static class SensitiveFields {
        private String storageKey;
        private String checksumSha256;
    }
}
