package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CaseFileRepositoryTest extends CasePostgresTestSupport {
    @Autowired private CaseFileRepository files;
    @Autowired private CaseRecordRepository cases;

    private CaseFile upload(long uploader, Long caseId, String key, LocalDateTime uploadedAt, LocalDateTime deletedAt,
            CaseFilePurpose purpose) {
        return files.saveAndFlush(CaseFile.builder().caseId(caseId).uploadedBy(uploader).purpose(purpose)
                .visibility(purpose == CaseFilePurpose.REPORT ? FileVisibility.PRIVATE : FileVisibility.PUBLIC)
                .storageKey(key).contentType("application/pdf").sizeBytes(123).checksumSha256("a".repeat(64))
                .uploadedAt(uploadedAt).attachedAt(caseId == null ? null : uploadedAt).deletedAt(deletedAt).build());
    }

    @Test
    void listsOnlyLiveFilesOfTheCaseInUploadOrder() {
        long r = user("files@example.com");
        long a = cases.save(aCase(r).build()).getId(), b = cases.save(aCase(r).build()).getId();
        CaseFile later = upload(r, a, "later", at(10), null, CaseFilePurpose.PHOTO);
        CaseFile first = upload(r, a, "first", at(9), null, CaseFilePurpose.REPORT);
        CaseFile tied = upload(r, a, "tie", at(10), null, CaseFilePurpose.PHOTO);
        upload(r, a, "deleted", at(8), at(11), CaseFilePurpose.REPORT);
        upload(r, b, "other", at(8), null, CaseFilePurpose.REPORT);
        assertThat(files.findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(a)).extracting(CaseFile::getId)
                .containsExactly(first.getId(), later.getId(), tied.getId());
    }

    @Test
    void detectsWhetherAReportIsAttached() {
        long r = user("report@example.com"), c = cases.save(aCase(r).build()).getId();
        upload(r, c, "photo", at(9), null, CaseFilePurpose.PHOTO);
        upload(r, c, "deleted-report", at(9), at(10), CaseFilePurpose.REPORT);
        assertThat(files.existsByCaseIdAndPurposeAndDeletedAtIsNull(c, CaseFilePurpose.REPORT)).isFalse();
        upload(r, c, "live-report", at(9), null, CaseFilePurpose.REPORT);
        assertThat(files.existsByCaseIdAndPurposeAndDeletedAtIsNull(c, CaseFilePurpose.REPORT)).isTrue();
    }

    @Test
    void findsUnattachedUploadsByUploader() {
        long a = user("upload-a@example.com"), b = user("upload-b@example.com");
        long c = cases.save(aCase(a).build()).getId();
        CaseFile pending = upload(a, null, "pending", at(9), null, CaseFilePurpose.REPORT);
        upload(a, c, "attached", at(9), null, CaseFilePurpose.REPORT);
        upload(b, null, "other-pending", at(9), null, CaseFilePurpose.REPORT);
        assertThat(files.findByUploadedByAndCaseIdIsNull(a)).extracting(CaseFile::getId).containsExactly(pending.getId());
    }

    @Test
    void recordsWhoUploaded() {
        long r = user("uploader@example.com");
        CaseFile file = upload(r, null, "who", at(9), null, CaseFilePurpose.REPORT);
        assertThat(files.findById(file.getId())).map(CaseFile::getUploadedBy).contains(r);
    }

    @ParameterizedTest @EnumSource(CaseFilePurpose.class)
    void everyFilePurposeAndVisibilityIsAcceptedByTheDatabase(CaseFilePurpose purpose) {
        long r = user("file-enums@example.com");
        assertThat(upload(r, null, "enum-file", at(9), null, purpose).getId()).isNotNull();
    }
}
