package com.souldealers.crowdtracebackend.modules.casefile;

import java.io.InputStream;
import java.util.Optional;

public interface CaseFileUploadService {
    CaseFileMetadataResponse upload(Long uploaderId, CaseFilePurpose purpose, InputStream content,
            Optional<String> clientSha256);
}
