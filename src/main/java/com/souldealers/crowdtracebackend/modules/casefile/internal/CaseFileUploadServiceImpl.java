package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFileUploadService;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.CaseFileProperties;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.FileContentInspector;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.SpooledUpload;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.FileStorage;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CaseFileUploadServiceImpl implements CaseFileUploadService {
    private final CaseFileRepository fileRepository;
    private final FileContentInspector inspector;
    private final CaseFileProperties properties;
    private final FileStorage storage;

    @Override
    public CaseFileMetadataResponse upload(Long uploaderId, CaseFilePurpose purpose, InputStream content,
            Optional<String> clientSha256) {
        try (SpooledUpload upload = SpooledUpload.from(content, properties.getMaxSizeBytes())) {
            String contentType = inspector.inspect(upload.path(), purpose).contentType();
            if (clientSha256.isPresent() && !clientSha256.get().equalsIgnoreCase(upload.checksumSha256())) {
                throw new ValidationException("File checksum does not match uploaded content");
            }
            StorageKey key = StorageKey.generate(purpose);
            StoredObject metadata = new StoredObject(key, purpose.visibility(), contentType,
                    upload.sizeBytes(), upload.checksumSha256());
            CaseFile file = CaseFile.builder().uploadedBy(uploaderId).purpose(purpose).visibility(metadata.visibility())
                    .storageKey(key.value()).contentType(metadata.contentType()).sizeBytes(metadata.sizeBytes())
                    .checksumSha256(metadata.checksumSha256()).build();

            store(metadata, upload.path());
            deleteOnRollback(key, file);
            return CaseMapper.toFileMetadata(fileRepository.save(file));
        } catch (IOException failure) {
            throw new RuntimeException("File upload failed");
        }
    }

    private void store(StoredObject metadata, Path content) throws IOException {
        try {
            storage.put(metadata, content);
        } catch (RuntimeException failure) {
            throw new RuntimeException("File upload failed");
        }
    }

    private void deleteOnRollback(StorageKey key, CaseFile file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(key);
                    } catch (IOException | RuntimeException failure) {
                        log.warn("Failed to clean up uploaded file {}", file.getId());
                    }
                }
            }
        });
    }
}
