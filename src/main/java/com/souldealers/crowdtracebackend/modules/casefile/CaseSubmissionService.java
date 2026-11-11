package com.souldealers.crowdtracebackend.modules.casefile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface CaseSubmissionService {
    CaseSubmissionResponse submit(Long reporterId, @NotNull @Valid CaseSubmissionRequest request);
}
