package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;

/**
 * File metadata for review. {@code accessAvailable} is false until authorized signed access exists (CT-023/030);
 * there is no URL or storage key.
 */
public record ReviewEvidenceResponse(CaseFileMetadataResponse file, boolean accessAvailable) {

    public static ReviewEvidenceResponse metadataOnly(CaseFileMetadataResponse file) {
        return new ReviewEvidenceResponse(file, false);
    }
}
