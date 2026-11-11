package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

/** Admin file metadata from PRD §5.1; storage keys and checksums are never exposed. */
public record CaseFileMetadataResponse(Long id, CaseFilePurpose purpose, FileVisibility visibility,
        String contentType, long sizeBytes, LocalDateTime uploadedAt) {}
