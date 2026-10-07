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
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.REPORT),
                upload(reporter, CaseFilePurpose.PHOTO), upload(reporter, CaseFilePurpose.PHOTO));

        attach(caseId, reporter, ids);

        List<CaseFile> attached = files.findAllById(ids);
        assertThat(attached).extracting(CaseFile::getCaseId).containsOnly(caseId);
        assertThat(attached).extracting(CaseFile::getAttachedAt).doesNotContainNull().containsOnly(
                attached.getFirst().getAttachedAt());
    }

    @Test
    void rejectsASubmissionWithoutAReport() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, ids)).isInstanceOf(ValidationException.class)
                .hasMessage("A missing-person report is required");
        assertUnattached(ids);
    }

    @Test
    void rejectsSixPhotosAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = new ArrayList<>(List.of(upload(reporter, CaseFilePurpose.REPORT)));
        for (int i = 0; i < 6; i++) ids.add(upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, ids)).isInstanceOf(ValidationException.class);
        assertUnattached(ids);
    }

    @Test
    void submissionPhotoCapCountsExistingLivePhotos() {
        long reporter = reporter(), caseId = ownCase(reporter);
        existingPhotos(caseId, reporter, 5);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.REPORT), upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, ids)).isInstanceOf(ValidationException.class);
        assertUnattached(ids);
    }

    @Test
    void submissionUsesConfiguredPhotoCap() {
        properties.setMaxPhotosPerCase(1);
        long reporter = reporter(), caseId = ownCase(reporter);
        List<Long> ids = List.of(upload(reporter, CaseFilePurpose.REPORT), upload(reporter, CaseFilePurpose.PHOTO),
                upload(reporter, CaseFilePurpose.PHOTO));

        assertThatThrownBy(() -> attach(caseId, reporter, ids)).isInstanceOf(ValidationException.class);
        assertUnattached(ids);
    }

    @Test
    void rejectsEmptyIds() {
        long reporter = reporter(), caseId = ownCase(reporter);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNullIds() {
        long reporter = reporter(), caseId = ownCase(reporter);
        assertThatThrownBy(() -> attach(caseId, reporter, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNullIdWithinABatchAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, Arrays.asList(report, null)))
                .isInstanceOf(ValidationException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsDuplicateIdsAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, report)))
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
            assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, unavailable)))
                    .isInstanceOf(NotFoundException.class).hasMessage("File not found");
            assertUnattached(List.of(report, foreign, deleted));
        }
    }

    @Test
    void rejectsAnotherReportersCaseAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter()), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report))).isInstanceOf(NotFoundException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsAnUnknownCaseAtomically() {
        long reporter = reporter(), report = upload(reporter, CaseFilePurpose.REPORT);
        assertThatThrownBy(() -> attach(Long.MAX_VALUE, reporter, List.of(report)))
                .isInstanceOf(NotFoundException.class);
        assertUnattached(List.of(report));
    }

    @Test
    void rejectsAnAlreadyAttachedFileAtomically() {
        long reporter = reporter(), caseId = ownCase(reporter), otherCase = ownCase(reporter);
        long attached = upload(reporter, CaseFilePurpose.REPORT);
        attach(otherCase, reporter, List.of(attached));
        long report = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report, attached)))
                .isInstanceOf(ConflictException.class);
        assertUnattached(List.of(report));
        assertThat(files.findById(attached).orElseThrow().getCaseId()).isEqualTo(otherCase);
    }

    @Test
    void rejectsAttachingTheSameFileAgainToTheSameCase() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);
        attach(caseId, reporter, List.of(report));

        assertThatThrownBy(() -> attach(caseId, reporter, List.of(report))).isInstanceOf(ConflictException.class);
    }

    @Test
    void requiresAnExistingTransaction() {
        assertThatThrownBy(() -> service.attachSubmission(1L, 1L, List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void submissionRollbackAlsoRollsBackAttachments() {
        long reporter = reporter(), caseId = ownCase(reporter), report = upload(reporter, CaseFilePurpose.REPORT);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.attachSubmission(caseId, reporter, List.of(report));
            throw new IllegalStateException("Submission failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Submission failed");
        assertUnattached(List.of(report));
    }

    @Test
    void competingSubmissionsCanAttachAReportOnlyOnce() throws Exception {
        long reporter = reporter(), firstCase = ownCase(reporter), secondCase = ownCase(reporter);
        long report = upload(reporter, CaseFilePurpose.REPORT);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> competingAttach(firstCase, reporter, report, ready, start));
            var second = executor.submit(() -> competingAttach(secondCase, reporter, report, ready, start));
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

    private Throwable competingAttach(long caseId, long reporter, long report, CountDownLatch ready,
            CountDownLatch start) {
        return catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            service.attachSubmission(caseId, reporter, List.of(report));
        }));
    }

    private void attach(long caseId, long reporter, List<Long> ids) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.attachSubmission(caseId, reporter, ids));
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
