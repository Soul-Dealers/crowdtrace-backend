package com.souldealers.crowdtracebackend.modules.casefile;

public interface CaseLifecycleService {
    CaseLifecycleResponse updateStatus(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
    CaseLifecycleResponse updateStatusAsAdmin(String actorEmail, Long caseId, UpdateCaseStatusRequest request);
    CaseLifecycleResponse setClosingStatement(String actorEmail, Long caseId, SetClosingStatementRequest request);
    CaseLifecycleResponse setClosingStatementAsAdmin(String actorEmail, Long caseId,
                                                     SetClosingStatementRequest request);
    CaseLifecycleResponse takeDownAsAdmin(String actorEmail, Long caseId, TakeDownCaseRequest request);
    CaseLifecycleResponse restoreAsAdmin(String actorEmail, Long caseId, RestoreCaseRequest request);
}
