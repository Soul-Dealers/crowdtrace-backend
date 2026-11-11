package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;
import java.util.List;

public record CasePhotosAddedResponse(Long caseId, long caseVersion, List<CaseFileMetadataResponse> photos,
                                      LocalDateTime addedAt) {
    public CasePhotosAddedResponse {
        photos = List.copyOf(photos);
    }
}
