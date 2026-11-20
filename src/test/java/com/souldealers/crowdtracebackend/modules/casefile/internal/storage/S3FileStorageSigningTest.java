package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Signing is offline, so the disposition baked into the URL is checked without a bucket. */
class S3FileStorageSigningTest {
    private static final URI ENDPOINT = URI.create("https://s3.test");
    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret"));

    private final S3FileStorage storage = new S3FileStorage(
            S3Client.builder().endpointOverride(ENDPOINT).region(Region.EU_WEST_1).credentialsProvider(CREDENTIALS)
                    .build(),
            S3Presigner.builder().endpointOverride(ENDPOINT).region(Region.EU_WEST_1).credentialsProvider(CREDENTIALS)
                    .build(),
            "case-files", Duration.ofMinutes(15));

    @Test
    void privateReportDownloadsUnlessAPreviewAsksForInline() {
        StoredObject report = new StoredObject(StorageKey.generate(CaseFilePurpose.REPORT),
                CaseFilePurpose.REPORT.visibility(), "application/pdf", 10, "0".repeat(64));

        assertThat(storage.signedDownloadUrl(report, Duration.ofMinutes(1), false).getQuery())
                .contains("response-content-disposition=attachment").contains("response-cache-control=no-store");
        assertThat(storage.signedDownloadUrl(report, Duration.ofMinutes(1), true).getQuery())
                .contains("response-content-disposition=inline").contains("response-cache-control=no-store");
    }
}
