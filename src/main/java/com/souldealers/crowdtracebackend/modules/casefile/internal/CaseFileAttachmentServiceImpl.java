package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAttachmentService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.CaseFileProperties;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CaseFileAttachmentServiceImpl implements CaseFileAttachmentService {
    private final CaseRecordRepository caseRepository;
    private final CaseFileRepository fileRepository;
    private final CaseFileProperties properties;

    @Override
    public void attachSubmission(Long caseId, Long reporterId, List<Long> fileIds) {
        attach(caseId, reporterId, fileIds, true, Set.of(CaseFilePurpose.REPORT, CaseFilePurpose.PHOTO));
    }

    @Override
    public void attachPhotos(Long caseId, Long reporterId, List<Long> photoIds) {
        attach(caseId, reporterId, photoIds, false, Set.of(CaseFilePurpose.PHOTO));
    }

    private void attach(Long caseId, Long reporterId, List<Long> fileIds, boolean requireReport,
            Set<CaseFilePurpose> allowedPurposes) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.stream().anyMatch(id -> id == null)
                || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw new ValidationException("File ids must be non-empty, non-null and distinct");
        }

        caseRepository.findOwnedByIdForUpdate(caseId, reporterId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        List<CaseFile> files = fileRepository.findAllByIdInOrderByIdAsc(fileIds);
        if (files.size() != fileIds.size() || files.stream().anyMatch(file ->
                !reporterId.equals(file.getUploadedBy()) || file.getDeletedAt() != null)) {
            throw new NotFoundException("File not found");
        }
        if (files.stream().anyMatch(file -> file.getCaseId() != null)) {
            throw new ConflictException("File is already attached to a case");
        }
        if (files.stream().anyMatch(file -> !allowedPurposes.contains(file.getPurpose()))) {
            throw new ValidationException("File purpose is not allowed for this attachment");
        }
        if (requireReport && files.stream().noneMatch(file -> file.getPurpose() == CaseFilePurpose.REPORT)) {
            throw new ValidationException("A missing-person report is required");
        }
        long newPhotos = files.stream().filter(file -> file.getPurpose() == CaseFilePurpose.PHOTO).count();
        long existingPhotos = fileRepository.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId,
                CaseFilePurpose.PHOTO);
        if (existingPhotos + newPhotos > properties.getMaxPhotosPerCase()) {
            throw new ValidationException("Case photo limit exceeded");
        }

        LocalDateTime attachedAt = LocalDateTime.now();
        files.forEach(file -> file.attachTo(caseId, attachedAt));
    }
}
