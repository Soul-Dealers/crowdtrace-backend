package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 storage settings. Credentials come from the AWS default provider chain (environment, profile or role).
 *
 * @param bucket private bucket holding every case file; setting it switches storage from memory to S3
 * @param region bucket region; when null the SDK resolves it from the environment
 * @param endpoint optional S3-compatible endpoint (MinIO, LocalStack, R2); must be reachable by browsers
 *                 because presigned URLs embed it
 * @param pathStyleAccess required by most S3-compatible servers
 * @param maxDownloadTtl upper bound for signed URL lifetimes
 */
@ConfigurationProperties(prefix = "crowdtrace.storage.s3")
public record S3StorageProperties(String bucket, String region, URI endpoint, boolean pathStyleAccess,
                                  Duration maxDownloadTtl) {
    public S3StorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("crowdtrace.storage.s3.bucket must not be blank when S3 storage is enabled");
        }
        if (maxDownloadTtl == null) {
            maxDownloadTtl = Duration.ofMinutes(15);
        }
        if (maxDownloadTtl.isNegative() || maxDownloadTtl.isZero()) {
            throw new IllegalStateException("crowdtrace.storage.s3.max-download-ttl must be positive");
        }
    }
}
