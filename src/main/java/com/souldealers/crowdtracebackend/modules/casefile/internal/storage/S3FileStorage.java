package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Stores case files in one private bucket under generated keys ({@code reports/…}, {@code photos/…}).
 * Encryption at rest comes from the bucket's default encryption, and public access must be blocked on the
 * bucket. Provider failures surface as {@link IOException} without the storage key.
 */
public class S3FileStorage implements FileStorage, SignedFileAccess {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration maxDownloadTtl;

    public S3FileStorage(S3Client client, S3Presigner presigner, String bucket, Duration maxDownloadTtl) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.maxDownloadTtl = maxDownloadTtl;
    }

    @Override
    public void put(StoredObject metadata, Path content) throws IOException {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(metadata.key().value())
                .contentType(metadata.contentType())
                .contentLength(metadata.sizeBytes())
                // Stored checksums are hex; S3 verifies against the base64 of the digest bytes.
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(metadata.checksumSha256())))
                // Keys are generated, so an existing object means a collision, never an overwrite.
                .ifNoneMatch("*")
                .build();
        try {
            client.putObject(request, RequestBody.fromFile(content));
        } catch (SdkException failure) {
            throw new IOException("Object storage rejected the upload", failure);
        }
    }

    @Override
    public void delete(StorageKey key) throws IOException {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (SdkException failure) {
            throw new IOException("Object storage rejected the delete", failure);
        }
    }

    @Override
    public URI signedDownloadUrl(StoredObject object, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxDownloadTtl) > 0) {
            throw new IllegalArgumentException("Download URL lifetime must be positive and at most " + maxDownloadTtl);
        }
        boolean isPrivate = object.visibility() == FileVisibility.PRIVATE;
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(object.key().value())
                .responseContentType(object.contentType())
                .responseContentDisposition(isPrivate ? "attachment" : "inline")
                // Private files must not linger in caches; public photos may be cached only as long as the URL lives.
                .responseCacheControl(isPrivate ? "no-store" : "private, max-age=" + ttl.toSeconds())
                .build();
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl).getObjectRequest(get).build()).url().toURI();
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("Presigner produced an invalid URL", failure);
        }
    }
}
