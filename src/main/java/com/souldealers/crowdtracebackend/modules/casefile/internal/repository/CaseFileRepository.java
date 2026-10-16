package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {
    List<CaseFile> findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);
    List<CaseFile> findByUploadedByAndCaseIdIsNullAndDeletedAtIsNull(Long uploadedBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CaseFile> findAllByIdInOrderByIdAsc(List<Long> ids);

    long countByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);

    @Query(value = """
            SELECT DISTINCT ON (case_id) case_id AS caseId, id AS photoId
            FROM case_files
            WHERE case_id IN (:caseIds) AND purpose = 'PHOTO' AND visibility = 'PUBLIC' AND deleted_at IS NULL
            ORDER BY case_id, uploaded_at, id
            """, nativeQuery = true)
    List<CaseThumbnail> findThumbnails(@Param("caseIds") Collection<Long> caseIds);
}
