package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;

/**
 * File metadata for review. {@code accessAvailable} says whether the file URL endpoint can issue a download link;
 * there is no URL or storage key here.
 */
public record ReviewEvidenceResponse(CaseFileMetadataResponse file, boolean accessAvailable) {

}
