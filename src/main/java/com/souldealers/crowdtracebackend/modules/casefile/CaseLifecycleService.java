package com.souldealers.crowdtracebackend.modules.casefile;

public interface CaseLifecycleService {
    CaseLifecycleResponse updateStatus(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
    CaseLifecycleResponse updateStatusAsAdmin(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
    CaseLifecycleResponse setClosingStatement(String actorEmail, Long caseId, SetClosingStatementRequest request);
    CaseLifecycleResponse setClosingStatementAsAdmin(String actorEmail, Long caseId,
                                                     SetClosingStatementRequest request);
}
