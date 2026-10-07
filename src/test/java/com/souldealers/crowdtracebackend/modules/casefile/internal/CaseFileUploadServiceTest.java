package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFileUploadService;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.FileStorage;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(CaseFileUploadServiceTest.StorageConfiguration.class)
@TestPropertySource(properties = "crowdtrace.case-files.max-size-bytes=128")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class CaseFileUploadServiceTest extends CasePostgresTestSupport {
    private static final byte[] PDF = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452"
            + "00000001000000010802000000907753de0000000049454e44ae426082");

    @Autowired private CaseFileUploadService service;
    @Autowired private CaseFileRepository files;
    @Autowired private RecordingFileStorage storage;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetStorageAndFiles() {
        files.deleteAll();
        storage.reset();
    }

    @Test
    void recordsOnlyServerMeasuredReportMetadata() {
        long uploader = user("upload-report@example.com");

        CaseFileMetadataResponse response = upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty());

        CaseFile file = files.findById(response.id()).orElseThrow();
        assertThat(file.getCaseId()).isNull();
        assertThat(file.getAttachedAt()).isNull();
        assertThat(file.getUploadedBy()).isEqualTo(uploader);
        assertThat(file.getPurpose()).isEqualTo(CaseFilePurpose.REPORT);
        assertThat(file.getVisibility()).isEqualTo(FileVisibility.PRIVATE);
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getSizeBytes()).isEqualTo(PDF.length);
        assertThat(file.getChecksumSha256()).isEqualTo(checksum(PDF));
        assertThat(file.getStorageKey()).matches("^reports/[0-9a-f-]{36}$");
        assertThat(file.getUploadedAt()).isNotNull();
        assertThat(storage.lastMetadata).isEqualTo(new StoredObject(new StorageKey(file.getStorageKey()),
                file.getVisibility(), file.getContentType(), file.getSizeBytes(), file.getChecksumSha256()));
        assertThat(storage.objects.get(storage.lastMetadata.key())).containsExactly(PDF);
        assertThat(response).isEqualTo(new CaseFileMetadataResponse(file.getId(), file.getPurpose(),
                file.getVisibility(), file.getContentType(), file.getSizeBytes(), file.getUploadedAt()));
        assertThat(Arrays.stream(CaseFileMetadataResponse.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("id", "purpose", "visibility", "contentType", "sizeBytes", "uploadedAt");
        assertThat(storage.lastPath).doesNotExist();
    }

    @Test
    void ignoresClientFilenameAndType() throws NoSuchMethodException {
        assertThat(CaseFileUploadService.class.getDeclaredMethods()).hasSize(1);
        assertThat(CaseFileUploadService.class.getMethod("upload", Long.class, CaseFilePurpose.class,
                InputStream.class, Optional.class).getReturnType()).isEqualTo(CaseFileMetadataResponse.class);
        long uploader = user("upload-png-report@example.com");

        CaseFileMetadataResponse response = upload(uploader, CaseFilePurpose.REPORT, PNG, Optional.empty());

        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(storage.lastMetadata.contentType()).isEqualTo("image/png");
        assertThat(storage.lastMetadata.key().value()).matches("^reports/[0-9a-f-]{36}$");
    }

    @Test
    void photoVisibilityIsDerivedFromPurpose() {
        long uploader = user("upload-photo@example.com");

        CaseFileMetadataResponse response = upload(uploader, CaseFilePurpose.PHOTO, PNG, Optional.empty());

        assertThat(response.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(storage.lastMetadata.visibility()).isEqualTo(FileVisibility.PUBLIC);
        assertThat(storage.lastMetadata.key().value()).matches("^photos/[0-9a-f-]{36}$");
    }

    @Test
    void rejectsOversizeBeforeStorage() {
        assertRejected(new byte[129], CaseFilePurpose.REPORT, Optional.empty());
    }

    @Test
    void rejectsEmptyContentBeforeStorage() {
        assertRejected(new byte[0], CaseFilePurpose.REPORT, Optional.empty());
    }

    @Test
    void rejectsWrongTypeForPurposeBeforeStorage() {
        assertRejected(PDF, CaseFilePurpose.PHOTO, Optional.empty());
    }

    @Test
    void acceptsMatchingClientChecksum() {
        long uploader = user("matching-checksum@example.com");

        upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.of(checksum(PDF)));

        assertThat(files.count()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
    }

    @Test
    void acceptsUppercaseMatchingChecksumAndPersistsServerChecksum() {
        long uploader = user("uppercase-checksum@example.com");

        CaseFileMetadataResponse response = upload(uploader, CaseFilePurpose.REPORT, PDF,
                Optional.of(checksum(PDF).toUpperCase()));

        assertThat(files.findById(response.id()).orElseThrow().getChecksumSha256()).isEqualTo(checksum(PDF));
    }

    @Test
    void rejectsMismatchedClientChecksumBeforeStorage() {
        assertRejected(PDF, CaseFilePurpose.REPORT, Optional.of("b".repeat(64)));
    }

    @Test
    void deletesStoredObjectWhenTransactionRollsBack() {
        assertThatThrownBy(() -> upload(Long.MAX_VALUE, CaseFilePurpose.REPORT, PDF, Optional.empty()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(storage.putCalls).isEqualTo(1);
        assertThat(storage.deleteCalls).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(files.count()).isZero();
        assertThat(storage.lastPath).doesNotExist();
    }

    @Test
    void deletesStoredObjectWhenOuterTransactionRollsBack() {
        long uploader = user("outer-rollback@example.com");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty());
            assertThat(storage.objects).hasSize(1);
            throw new IllegalStateException("Submission failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Submission failed");

        assertThat(storage.deleteCalls).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(files.count()).isZero();
    }

    @Test
    void deletesStoredObjectWhenCommitFails() {
        long uploader = user("commit-failure@example.com");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("Commit failed");
                }
            });
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessage("Commit failed");

        assertThat(storage.deleteCalls).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(files.count()).isZero();
    }

    @Test
    void keepsStoredObjectWhenOuterTransactionCommits() {
        long uploader = user("outer-commit@example.com");

        new TransactionTemplate(transactionManager).execute(status ->
                upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()));

        assertThat(storage.objects).hasSize(1);
        assertThat(storage.deleteCalls).isZero();
        assertThat(files.count()).isEqualTo(1);
    }

    @Test
    void sanitizesStorageRuntimeFailure() {
        long uploader = user("storage-runtime-failure@example.com");
        storage.failPut = true;

        assertThatThrownBy(() -> upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()))
                .isExactlyInstanceOf(RuntimeException.class).hasMessage("File upload failed").hasNoCause();

        assertThat(storage.objects).isEmpty();
        assertThat(files.count()).isZero();
        assertThat(storage.lastPath).doesNotExist();
    }

    @Test
    void sanitizesStorageIoFailure() {
        long uploader = user("storage-io-failure@example.com");
        storage.failPutIo = true;

        assertThatThrownBy(() -> upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()))
                .isExactlyInstanceOf(RuntimeException.class).hasMessage("File upload failed").hasNoCause();

        assertThat(files.count()).isZero();
        assertThat(storage.lastPath).doesNotExist();
    }

    @Test
    void sanitizesInputReadFailure() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("secret client content");
            }
        };

        assertThatThrownBy(() -> service.upload(1L, CaseFilePurpose.REPORT, broken, Optional.empty()))
                .isExactlyInstanceOf(RuntimeException.class).hasMessage("File upload failed").hasNoCause();

        assertThat(storage.putCalls).isZero();
        assertThat(files.count()).isZero();
    }

    @Test
    void temporaryFileCleanupFailureRollsBackUpload() throws IOException {
        long uploader = user("temp-cleanup-failure@example.com");
        storage.failTempCleanup = true;

        try {
            assertThatThrownBy(() -> upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()))
                    .isExactlyInstanceOf(RuntimeException.class).hasMessage("File upload failed").hasNoCause();

            assertThat(files.count()).isZero();
            assertThat(storage.deleteCalls).isEqualTo(1);
            assertThat(storage.objects).isEmpty();
        } finally {
            if (storage.lastPath != null) {
                Files.deleteIfExists(storage.lastPath.resolve("blocker"));
                Files.deleteIfExists(storage.lastPath);
            }
        }
    }

    @Test
    void failedRollbackCleanupLogsOnlyFileId(CapturedOutput output) {
        long uploader = user("delete-failure@example.com");
        storage.failDelete = true;
        Long[] fileId = new Long[1];

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            fileId[0] = upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()).id();
            throw new IllegalStateException("Submission failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Submission failed");

        assertThat(storage.deleteCalls).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        assertThat(files.count()).isZero();
        assertThat(output).contains("Failed to clean up uploaded file " + fileId[0])
                .doesNotContain(storage.lastMetadata.key().value(), storage.lastMetadata.checksumSha256(),
                        "secret storage error");
    }

    @Test
    void runtimeRollbackCleanupFailureDoesNotReplaceOriginalFailure(CapturedOutput output) {
        long uploader = user("runtime-delete-failure@example.com");
        storage.failDeleteRuntime = true;
        Long[] fileId = new Long[1];

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            fileId[0] = upload(uploader, CaseFilePurpose.REPORT, PDF, Optional.empty()).id();
            throw new IllegalStateException("Submission failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Submission failed");

        assertThat(files.count()).isZero();
        assertThat(storage.deleteCalls).isEqualTo(1);
        assertThat(output).contains("Failed to clean up uploaded file " + fileId[0])
                .doesNotContain(storage.lastMetadata.key().value(), storage.lastMetadata.checksumSha256(),
                        "secret storage error");
    }

    private CaseFileMetadataResponse upload(long uploader, CaseFilePurpose purpose, byte[] bytes,
            Optional<String> clientChecksum) {
        return service.upload(uploader, purpose, new ByteArrayInputStream(bytes), clientChecksum);
    }

    private void assertRejected(byte[] bytes, CaseFilePurpose purpose, Optional<String> clientChecksum) {
        assertThatThrownBy(() -> upload(1L, purpose, bytes, clientChecksum)).isInstanceOf(ValidationException.class);
        assertThat(storage.putCalls).isZero();
        assertThat(files.count()).isZero();
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        RecordingFileStorage recordingFileStorage() {
            return new RecordingFileStorage();
        }
    }

    static class RecordingFileStorage implements FileStorage {
        final Map<StorageKey, byte[]> objects = new LinkedHashMap<>();
        StoredObject lastMetadata;
        Path lastPath;
        int putCalls;
        int deleteCalls;
        boolean failPut;
        boolean failPutIo;
        boolean failDelete;
        boolean failDeleteRuntime;
        boolean failTempCleanup;

        @Override
        public void put(StoredObject metadata, Path content) throws IOException {
            putCalls++;
            lastMetadata = metadata;
            lastPath = content;
            if (failPut) {
                throw new IllegalStateException("Storage failed for " + metadata.key().value());
            }
            if (failPutIo) {
                throw new IOException("Storage failed for " + metadata.key().value());
            }
            objects.put(metadata.key(), Files.readAllBytes(content));
            if (failTempCleanup) {
                Files.delete(content);
                Files.createDirectory(content);
                Files.writeString(content.resolve("blocker"), "prevent temporary path deletion");
            }
        }

        @Override
        public void delete(StorageKey key) throws IOException {
            deleteCalls++;
            if (failDelete) {
                throw new IOException("secret storage error " + key.value());
            }
            if (failDeleteRuntime) {
                throw new IllegalStateException("secret storage error " + key.value());
            }
            objects.remove(key);
        }

        void reset() {
            objects.clear();
            lastMetadata = null;
            lastPath = null;
            putCalls = 0;
            deleteCalls = 0;
            failPut = false;
            failPutIo = false;
            failDelete = false;
            failDeleteRuntime = false;
            failTempCleanup = false;
        }
    }
}
