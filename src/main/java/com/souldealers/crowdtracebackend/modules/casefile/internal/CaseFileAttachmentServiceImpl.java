package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAttachmentService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CasePhotoAttachmentResult;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.CaseFileProperties;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final EntityManager entityManager;
    private final Clock clock;

    @Override
    public void attachSubmission(Long caseId, Long reporterId, List<Long> reportFileIds, List<Long> photoFileIds) {
        if (reportFileIds == null || reportFileIds.isEmpty()) {
            throw new ValidationException("A missing-person report is required");
        }
        List<Long> fileIds = new ArrayList<>(reportFileIds);
        if (photoFileIds != null) fileIds.addAll(photoFileIds);
        attach(caseId, reporterId, fileIds, new HashSet<>(reportFileIds));
    }

    @Override
    public void attachPhotos(Long caseId, Long reporterId, List<Long> photoIds) {
        attach(caseId, reporterId, photoIds, Set.of());
    }

    private void attach(Long caseId, Long reporterId, List<Long> fileIds, Set<Long> reportFileIds) {
        validateFileIds(fileIds);
        CaseRecord record = caseRepository.findOwnedByIdForUpdate(caseId, reporterId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        attach(record, reporterId, fileIds, reportFileIds);
    }

    @Override
    public CasePhotoAttachmentResult attachLivePhotos(Long caseId, Long reporterId, long expectedCaseVersion,
                                                       List<Long> photoIds) {
        validateFileIds(photoIds);
        CaseRecord record = caseRepository.findOwnedByIdForUpdate(caseId, reporterId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        if (record.getReviewStatus() != ReviewStatus.APPROVED) {
            throw new ConflictException("Only an approved case can receive photos");
        }
        if (record.getVersion() != expectedCaseVersion) {
            throw new ConflictException("Case has changed since it was opened");
        }
        List<CaseFile> attached = attach(record, reporterId, photoIds, Set.of());
        LocalDateTime attachedAt = attached.getFirst().getAttachedAt();
        entityManager.flush();
        HashMap<Long, CaseFile> byId = new HashMap<>();
        attached.forEach(file -> byId.put(file.getId(), file));
        List<CaseFileMetadataResponse> metadata = photoIds.stream().map(byId::get)
                .map(CaseMapper::toFileMetadata).toList();
        return new CasePhotoAttachmentResult(record.getVersion(), metadata, attachedAt);
    }

    private List<CaseFile> attach(CaseRecord record, Long reporterId, List<Long> fileIds, Set<Long> reportFileIds) {
        List<CaseFile> files = fileRepository.findAllByIdInOrderByIdAsc(fileIds);
        if (files.size() != fileIds.size() || files.stream().anyMatch(file ->
                !reporterId.equals(file.getUploadedBy()) || file.getDeletedAt() != null)) {
            throw new NotFoundException("File not found");
        }
        if (files.stream().anyMatch(file -> file.getCaseId() != null)) {
            throw new ConflictException("File is already attached to a case");
        }
        if (files.stream().anyMatch(file -> file.getPurpose() !=
                (reportFileIds.contains(file.getId()) ? CaseFilePurpose.REPORT : CaseFilePurpose.PHOTO))) {
            throw new ValidationException("File purpose is not allowed for this attachment");
        }
        long newPhotos = files.stream().filter(file -> file.getPurpose() == CaseFilePurpose.PHOTO).count();
        long existingPhotos = fileRepository.countByCaseIdAndPurposeAndDeletedAtIsNull(record.getId(),
                CaseFilePurpose.PHOTO);
        if (existingPhotos + newPhotos > properties.getMaxPhotosPerCase()) {
            throw new ValidationException("Case photo limit exceeded");
        }

        LocalDateTime attachedAt = LocalDateTime.now(clock);
        files.forEach(file -> file.attachTo(record.getId(), attachedAt));
        // Evidence changed but the case row did not; bump its version so stale review decisions get a 409.
        entityManager.lock(record, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return files;
    }

    private static void validateFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw new ValidationException("File ids must be non-empty, non-null, positive and distinct");
        }
    }
}
