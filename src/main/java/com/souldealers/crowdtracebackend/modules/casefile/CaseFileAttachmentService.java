package com.souldealers.crowdtracebackend.modules.casefile;

import java.util.List;

public interface CaseFileAttachmentService {
    void attachSubmission(Long caseId, Long reporterId, List<Long> fileIds);

    void attachPhotos(Long caseId, Long reporterId, List<Long> photoIds);
}
