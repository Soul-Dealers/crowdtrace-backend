package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpooledUploadTest {
    @Test
    void measuresAndHashesTheWrittenBytes() throws Exception {
        byte[] bytes = "actual upload bytes".getBytes(StandardCharsets.UTF_8);

        try (SpooledUpload upload = SpooledUpload.from(new ByteArrayInputStream(bytes), 100)) {
            assertThat(upload.sizeBytes()).isEqualTo(bytes.length);
            assertThat(Files.readAllBytes(upload.path())).isEqualTo(bytes);
            assertThat(upload.checksumSha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    @Test
    void acceptsExactlyTheSizeLimit() throws IOException {
        try (SpooledUpload upload = SpooledUpload.from(new ByteArrayInputStream(new byte[10]), 10)) {
            assertThat(upload.sizeBytes()).isEqualTo(10);
        }
    }

    @Test
    void rejectsOversizeAndDeletesItsTemporaryFile() throws IOException {
        Set<Path> before = temporaryUploads();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[100]);

        assertThatThrownBy(() -> SpooledUpload.from(input, 10)).isInstanceOf(ValidationException.class);

        assertThat(input.available()).isEqualTo(89);
        assertThat(temporaryUploads()).isEqualTo(before);
    }

    @Test
    void deletesTemporaryFileIfTheInputFails() throws IOException {
        Set<Path> before = temporaryUploads();
        InputStream input = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ >= 2) {
                    throw new IOException("Input failed");
                }
                return 'x';
            }
        };

        assertThatThrownBy(() -> SpooledUpload.from(input, 100)).isInstanceOf(IOException.class);

        assertThat(temporaryUploads()).isEqualTo(before);
    }

    @Test
    void closingDeletesTemporaryFileAndIsIdempotent() throws IOException {
        SpooledUpload upload = SpooledUpload.from(new ByteArrayInputStream(new byte[1]), 10);
        Path path = upload.path();
        assertThat(path).exists();

        upload.close();
        upload.close();

        assertThat(path).doesNotExist();
    }

    @Test
    void retainsCallerOwnershipOfTheInput() throws IOException {
        class CallerInput extends ByteArrayInputStream {
            private boolean closed;

            CallerInput() {
                super(new byte[1]);
            }

            @Override
            public void close() {
                closed = true;
            }
        }
        CallerInput input = new CallerInput();

        try (SpooledUpload ignored = SpooledUpload.from(input, 10)) {
            assertThat(input.closed).isFalse();
        }
        assertThat(input.closed).isFalse();
    }

    @Test
    void handlesALimitWithoutLongOverflow() throws IOException {
        try (SpooledUpload upload = SpooledUpload.from(new ByteArrayInputStream(new byte[1]), Long.MAX_VALUE)) {
            assertThat(upload.sizeBytes()).isEqualTo(1);
        }
    }

    @Test
    void rejectsAnInvalidLimitBeforeCreatingATemporaryFile() throws IOException {
        Set<Path> before = temporaryUploads();

        assertThatThrownBy(() -> SpooledUpload.from(new ByteArrayInputStream(new byte[1]), 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(temporaryUploads()).isEqualTo(before);
    }

    private Set<Path> temporaryUploads() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("ct-case-file-"))
                    .collect(Collectors.toSet());
        }
    }
}
