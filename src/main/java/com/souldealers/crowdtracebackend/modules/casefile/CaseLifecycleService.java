package com.souldealers.crowdtracebackend.modules.casefile;

public interface CaseLifecycleService {
    CaseLifecycleResponse updateStatus(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
    CaseLifecycleResponse updateStatusAsAdmin(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
}
