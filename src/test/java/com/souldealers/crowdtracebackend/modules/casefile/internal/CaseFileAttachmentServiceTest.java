package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAttachmentService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.CaseFileProperties;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseFileAttachmentServiceTest extends CasePostgresTestSupport {
    @Autowired private CaseFileAttachmentService service;
    @Autowired private CaseFileRepository files;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseFileProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetFiles() {
        files.deleteAll();
    }

    @AfterEach
    void restorePhotoCap() {
        properties.setMaxPhotosPerCase(5);
    }

    @Test
    void attachesAReportAndPhotosWithOneAttachmentTime() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        List<Long> photos = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));
        List<Long> ids = List.of(report, photos.get(0), photos.get(1));

        attach(caseId, reporter, List.of(report), photos);

        List<CaseFile> attached = files.findAllById(ids);
        assertThat(attached).extracting(CaseFile::getCaseId).containsOnly(caseId);
        assertThat(attached).extracting(CaseFile::getAttachedAt).doesNotContainNull().containsOnly(
                attached.getFirst().getAttachedAt());
    }

    @Test
    void rejectsASubmissionWithoutAReport() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(), ids)).isInstanceOf(ValidationException.class)
                .hasMessage("A missing-person report is required");
        assertUnattached(ids);
    }

    @Test
    void attachesMultipleReports() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> reports = List.of(upload(reporter, CaseFilePurpose.REPORT),
                upload(reporter, CaseFilePurpose.REPORT));

        attach(caseId, reporter, reports, List.of());

        List<CaseFile> attached = files.findAllById(reports);
        assertThat(attached).extracting(CaseFile::getCaseId).containsOnly(caseId);
        assertThat(attached).extracting(CaseFile::getAttachedAt).doesNotContainNull();
    }

    @Test
    void acceptsANullPhotoList() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);

        attach(caseId, reporter, List.of(report), null);

        assertThat(files.findById(report).orElseThrow().getCaseId()).isEqualTo(caseId);
    }

    @Test
    void rejectsAPhotoInTheReportSlotAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT), photo = upload(reporter, CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, photo), List.of()))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report, photo));
    }

    @Test
    void rejectsAReportInThePhotoSlotAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT), secondReport = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(secondReport)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report, secondReport));
    }

    @Test
    void rejectsTheSameIdAcrossSlotsAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(report)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsDuplicatePhotoIdsAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT), photo = upload(reporter, CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(photo, photo)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report, photo));
    }

    @Test
    void rejectsANullPhotoIdAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT), photo = upload(reporter, CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), Arrays.asList(photo, null)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report, photo));
    }

    @Test
    void rejectsNonPositiveIdsInEitherSlotAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);

        for (long invalid : List.of(0L, -1L)) {
            assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, invalid), List.of()))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(invalid)))
                    .isInstanceOf(ValidationException.class);
        }
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsSixPhotosAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        List<Long> photos = new ArrayList<>();
        for (int i = 0; i < 6; i++) photos.add(upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), photos))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
        assertUnattached(photos);
    }

    @Test
    void submissionPhotoCapCountsExistingLivePhotos() {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 5);
        long report = upload(reporter, CaseFilePurpose.REPORT), photo = upload(reporter, CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(photo)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report, photo));
    }

    @Test
    void submissionUsesConfiguredPhotoCap() {
        properties.setMaxPhotosPerCase(1);
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        List<Long> photos = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), photos))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
        assertUnattached(photos);
    }

    @Test
    void rejectsEmptyReportIds() {
        long reporter = reporter(), caseId = ownCase(reporter);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of(), List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNullReportIds() {
        long reporter = reporter(), caseId = ownCase(reporter);
        assertThatThrownBy(() -> attach(caseId, reporter, null, List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsANullReportIdAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, Arrays.asList(report, null), List.of()))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsDuplicateReportIdsAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, report), List.of()))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void missingForeignAndDeletedFilesHaveTheSameNotFoundMessage() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        long foreign = upload(reporter(), CaseFilePurpose.PHOTO);
        long deleted = upload(reporter, CaseFilePurpose.PHOTO);
        jdbc.update("UPDATE case_files SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        for (long unavailable : List.of(Long.MAX_VALUE, foreign, deleted)) {
            assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of(unavailable)))
                    .isInstanceOf(NotFoundException.class).hasMessage("File not found");
            assertUnattached(List.of(report, foreign, deleted));
        }
    }

    @Test
    void unavailableReportsHaveTheSameNotFoundMessage() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        long foreign = upload(reporter(), CaseFilePurpose.REPORT);
        long deleted = upload(reporter, CaseFilePurpose.REPORT);
        jdbc.update("UPDATE case_files SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        for (long unavailable : List.of(Long.MAX_VALUE, foreign, deleted)) {
            assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, unavailable), List.of()))
                    .isInstanceOf(NotFoundException.class).hasMessage("File not found");
            assertUnattached(List.of(report, foreign, deleted));
        }
    }

    @Test
    void rejectsAnotherReportersCaseAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter()), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of()))
                .isInstanceOf(NotFoundException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsAnUnknownCaseAtomically() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(Long.MAX_VALUE, reporter, List.of(report), List.of()))
                .isInstanceOf(NotFoundException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsAnAlreadyAttachedFileAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), otherCase = ownCase(reporter);
        long attached = upload(reporter, CaseFilePurpose.REPORT);
        attach(otherCase, reporter, List.of(attached), List.of());
        long report = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, attached), List.of()))
                .isInstanceOf(ConflictException.class);
        assertUnattached(List.of(report));
        assertThat(files.findById(attached).orElseThrow().getCaseId()).isEqualTo(otherCase);
    }

    @Test
    void rejectsAttachingTheSameFileAgainToTheSameCase() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        attach(caseId, reporter, List.of(report), List.of());

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report), List.of()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void requiresAnExistingTransaction() {
        assertThatThrownBy(() -> service.attachSubmission(1L, 1L, List.of(1L), List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void submissionRollbackAlsoRollsBackAttachments() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.attachSubmission(caseId, reporter, List.of(report), List.of());
            throw new IllegalStateException("Submission failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Submission failed");
        assertUnattached(List.of(report));
    }

    @Test
    void attachesPhotosWithoutRequiringAReport() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));

        attachPhotos(caseId, reporter, ids);

        List<CaseFile> attached = files.findAllById(ids);
        assertThat(attached).extracting(CaseFile::getCaseId).containsOnly(caseId);
        assertThat(attached).extracting(CaseFile::getAttachedAt).doesNotContainNull()
                .containsOnly(attached.getFirst().getAttachedAt());
    }

    @Test
    void rejectsAReportInAPhotoBatchAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.REPORT));

        assertThatThrownBy(() -> attachPhotos(caseId, reporter, ids)).isInstanceOf(ValidationException.class);
        assertUnattached(ids);
    }

    @Test
    void acceptsOnePhotoWhenTheCaseAlreadyHasFourLivePhotos() {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 4);
        long photo = upload(reporter, CaseFilePurpose.PHOTO);

        attachPhotos(caseId, reporter, List.of(photo));

        assertThat(files.findById(photo).orElseThrow().getCaseId()).isEqualTo(caseId);
        assertThat(files.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.PHOTO)).isEqualTo(5);
    }

    @Test
    void rejectsTwoPhotosWhenTheCaseAlreadyHasFourLivePhotos() {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 4);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attachPhotos(caseId, reporter, ids)).isInstanceOf(ValidationException.class);

        assertUnattached(ids);
        assertThat(files.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.PHOTO)).isEqualTo(4);
    }

    @Test
    void deletedPhotosDoNotCountTowardsTheCap() {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 5);
        long deleted = files.findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(caseId).getFirst().getId();
        jdbc.update("UPDATE case_files SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
        long photo = upload(reporter, CaseFilePurpose.PHOTO);

        attachPhotos(caseId, reporter, List.of(photo));

        assertThat(files.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.PHOTO)).isEqualTo(5);
        assertThat(files.findById(deleted).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void photoAdditionsUseTheConfiguredCap() {
        properties.setMaxPhotosPerCase(2);
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 1);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attachPhotos(caseId, reporter, ids)).isInstanceOf(ValidationException.class);
        assertUnattached(ids);
        attachPhotos(caseId, reporter, List.of(ids.getFirst()));
        assertThat(files.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.PHOTO)).isEqualTo(2);
    }

    @Test
    void photoAdditionsRequireAnExistingTransaction() {
        assertThatThrownBy(() -> service.attachPhotos(1L, 1L, List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void photoBatchesUseTheSameOwnershipAndAvailabilityRules() {
        long reporter = reporter(), caseId = ownCase(reporter);
        long photo = upload(reporter, CaseFilePurpose.PHOTO);
        long foreign = upload(reporter(), CaseFilePurpose.PHOTO);
        long deleted = upload(reporter, CaseFilePurpose.PHOTO);
        jdbc.update("UPDATE case_files SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);

        for (long unavailable : List.of(Long.MAX_VALUE, foreign, deleted)) {
            assertThatThrownBy(() -> attachPhotos(caseId, reporter, List.of(photo, unavailable)))
                    .isInstanceOf(NotFoundException.class).hasMessage("File not found");
            assertUnattached(List.of(photo));
        }
    }

    @Test
    void photoAdditionsRollBackWithTheCaller() {
        long reporter = reporter(), caseId = ownCase(reporter), photo = upload(reporter, CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.attachPhotos(caseId, reporter, List.of(photo));
            throw new IllegalStateException("Photo update failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Photo update failed");
        assertUnattached(List.of(photo));
    }

    @Test
    void competingPhotoBatchesCannotExceedTheCaseCap() throws Exception {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 4);
        long firstPhoto = upload(reporter, CaseFilePurpose.PHOTO), secondPhoto = upload(reporter, CaseFilePurpose.PHOTO);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> competingAttach(() ->
                    service.attachPhotos(caseId, reporter, List.of(firstPhoto)), ready, start));
            var second = executor.submit(() -> competingAttach(() ->
                    service.attachPhotos(caseId, reporter, List.of(secondPhoto)), ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<Throwable> outcomes = Arrays.asList(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull).toList())
                    .singleElement().isInstanceOf(ValidationException.class);
        }
        assertThat(files.countByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.PHOTO)).isEqualTo(5);
        assertThat(files.findAllById(List.of(firstPhoto, secondPhoto))).filteredOn(file -> file.getCaseId() != null)
                .hasSize(1);
    }

    @Test
    void competingSubmissionsCanAttachAReportOnlyOnce() throws Exception {
        long reporter = reporter(), firstCase = ownCase(reporter), secondCase = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> competingAttach(() ->
                    service.attachSubmission(firstCase, reporter, List.of(report), List.of()), ready, start));
            var second = executor.submit(() -> competingAttach(() ->
                    service.attachSubmission(secondCase, reporter, List.of(report), List.of()), ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<Throwable> outcomes = Arrays.asList(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull).toList())
                    .singleElement().isInstanceOf(ConflictException.class);
        }
        assertThat(files.findById(report).orElseThrow().getCaseId()).isIn(firstCase, secondCase);
    }

    private Throwable competingAttach(Runnable attachment, CountDownLatch ready, CountDownLatch start) {
        return catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            attachment.run();
        }));
    }

    private void attach(long caseId, long reporter, List<Long> reportIds, List<Long> photoIds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.attachSubmission(caseId, reporter, reportIds, photoIds));
    }

    private void attachPhotos(long caseId, long reporter, List<Long> ids) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.attachPhotos(caseId, reporter, ids));
    }

    private long reporter() { return user(UUID.randomUUID() + "@example.com"); }

    private long ownCase(long reporter) { return cases.saveAndFlush(aCase(reporter).build()).getId(); }

    private long upload(long reporter, CaseFilePurpose purpose) {
        return files.saveAndFlush(CaseFile.builder().uploadedBy(reporter).purpose(purpose)
                .visibility(purpose.visibility()).storageKey(UUID.randomUUID().toString())
                .contentType(purpose == CaseFilePurpose.REPORT ? "application/pdf" : "image/png")
                .sizeBytes(123).checksumSha256("a".repeat(64)).build()).getId();
    }

    private void existingPhotos(long caseId, long reporter, int count) {
        for (int i = 0; i < count; i++) {
            long id = upload(reporter, CaseFilePurpose.PHOTO);
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    files.findById(id).orElseThrow().attachTo(caseId, LocalDateTime.now()));
        }
    }

    private void assertUnattached(List<Long> ids) {
        assertThat(files.findAllById(ids)).extracting(CaseFile::getCaseId).containsOnlyNulls();
        assertThat(files.findAllById(ids)).extracting(CaseFile::getAttachedAt).containsOnlyNulls();
    }
}
