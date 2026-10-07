package com.souldealers.crowdtracebackend.modules.casefile;

public enum CaseFilePurpose {
    REPORT(FileVisibility.PRIVATE, "reports"),
    PHOTO(FileVisibility.PUBLIC, "photos");

    private final FileVisibility visibility;
    private final String storagePrefix;

    CaseFilePurpose(FileVisibility visibility, String storagePrefix) {
        this.visibility = visibility;
        this.storagePrefix = storagePrefix;
    }

    public FileVisibility visibility() {
        return visibility;
    }

    public String storagePrefix() {
        return storagePrefix;
    }
}
