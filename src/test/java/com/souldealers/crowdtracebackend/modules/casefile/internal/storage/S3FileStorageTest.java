package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Exercises the real S3 protocol against LocalStack: checksums, conditional puts, signing and expiry. */
@Testcontainers
class S3FileStorageTest {
    private static final String BUCKET = "case-files";

    @Container
    static final LocalStackContainer S3 = new LocalStackContainer("localstack/localstack:4.4")
            .withServices("s3")
            // Validate presigned signatures and expiry instead of LocalStack's permissive default.
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");

    private static S3Client client;
    private static S3FileStorage storage;
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir Path dir;

    @BeforeAll
    static void createBucket() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(S3.getAccessKey(), S3.getSecretKey()));
        var paths = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        URI endpoint = S3.getEndpoint();
        Region region = Region.of(S3.getRegion());
        client = S3Client.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials).serviceConfiguration(paths).build();
        S3Presigner presigner = S3Presigner.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials).serviceConfiguration(paths).build();
        client.createBucket(b -> b.bucket(BUCKET));
        storage = new S3FileStorage(client, presigner, BUCKET, Duration.ofMinutes(15));
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void storedReportDownloadsAsAnAttachmentThroughASignedUrl() throws Exception {
        StoredObject report = stored(CaseFilePurpose.REPORT, "application/pdf", "%PDF-1.7 report");

        HttpResponse<String> response = get(storage.signedDownloadUrl(report, Duration.ofMinutes(1)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("%PDF-1.7 report");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("attachment");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void publicPhotoIsServedInline() throws Exception {
        StoredObject photo = stored(CaseFilePurpose.PHOTO, "image/jpeg", "jpeg-bytes");
        assertThat(photo.visibility()).isEqualTo(FileVisibility.PUBLIC);

        HttpResponse<String> response = get(storage.signedDownloadUrl(photo, Duration.ofMinutes(1)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue("inline");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("private, max-age=60");
    }

    @Test
    void signedUrlStopsWorkingAfterItsLifetime() throws Exception {
        StoredObject report = stored(CaseFilePurpose.REPORT, "application/pdf", "short-lived");
        URI url = storage.signedDownloadUrl(report, Duration.ofSeconds(1));

        Thread.sleep(2_500);

        assertThat(get(url).statusCode()).isEqualTo(403);
    }

    @Test
    void rejectsContentThatDoesNotMatchTheStoredChecksum() throws IOException {
        Path content = write("actual");
        StoredObject wrong = new StoredObject(StorageKey.generate(CaseFilePurpose.REPORT), FileVisibility.PRIVATE,
                "application/pdf", Files.size(content), sha256("different"));

        assertThatThrownBy(() -> storage.put(wrong, content)).isInstanceOf(IOException.class)
                .hasMessage("Object storage rejected the upload");
    }

    @Test
    void neverOverwritesAnExistingKey() throws Exception {
        StoredObject first = stored(CaseFilePurpose.REPORT, "application/pdf", "original");
        Path replacement = write("replacement");
        StoredObject collision = new StoredObject(first.key(), first.visibility(), first.contentType(),
                Files.size(replacement), sha256("replacement"));

        assertThatThrownBy(() -> storage.put(collision, replacement)).isInstanceOf(IOException.class);
        assertThat(get(storage.signedDownloadUrl(first, Duration.ofMinutes(1))).body()).isEqualTo("original");
    }

    @Test
    void deletedObjectsAreGone() throws Exception {
        StoredObject report = stored(CaseFilePurpose.REPORT, "application/pdf", "to delete");

        storage.delete(report.key());

        assertThat(get(storage.signedDownloadUrl(report, Duration.ofMinutes(1))).statusCode()).isEqualTo(404);
    }

    @Test
    void refusesLifetimesOutsideTheConfiguredBounds() throws Exception {
        StoredObject report = stored(CaseFilePurpose.REPORT, "application/pdf", "bounded");

        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(16)}) {
            assertThatThrownBy(() -> storage.signedDownloadUrl(report, ttl))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private StoredObject stored(CaseFilePurpose purpose, String contentType, String body) throws IOException {
        Path content = write(body);
        StoredObject object = new StoredObject(StorageKey.generate(purpose), purpose.visibility(), contentType,
                Files.size(content), sha256(body));
        storage.put(object, content);
        return object;
    }

    private Path write(String body) throws IOException {
        return Files.writeString(Files.createTempFile(dir, "upload", ".bin"), body);
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(URI url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
