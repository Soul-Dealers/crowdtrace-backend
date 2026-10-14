package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;
import java.util.List;

public record CasePhotoAttachmentResult(long caseVersion, List<CaseFileMetadataResponse> photos,
                                        LocalDateTime attachedAt) {
    public CasePhotoAttachmentResult {
        photos = List.copyOf(photos);
    }
}
