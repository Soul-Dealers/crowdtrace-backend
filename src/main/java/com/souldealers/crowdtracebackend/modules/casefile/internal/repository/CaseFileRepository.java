package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {
    List<CaseFile> findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);
    List<CaseFile> findByUploadedByAndCaseIdIsNullAndDeletedAtIsNull(Long uploadedBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CaseFile> findAllByIdInOrderByIdAsc(List<Long> ids);

    long countByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);
}
