package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {
    List<CaseFile> findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);
    List<CaseFile> findByUploadedByAndCaseIdIsNull(Long uploadedBy);
}
