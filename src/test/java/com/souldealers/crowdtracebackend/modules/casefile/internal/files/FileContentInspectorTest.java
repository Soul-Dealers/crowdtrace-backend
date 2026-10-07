package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileContentInspectorTest {
    private static final byte[] PNG = HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d49484452"
                    + "00000001000000010802000000907753de"
                    + "0000000049454e44ae426082");

    @TempDir
    Path directory;

    private final FileContentInspector inspector = new FileContentInspector();

    @Test
    void detectsPdfReport() throws IOException {
        DetectedType type = inspector.inspect(writeAscii("%PDF-1.7\n%%EOF"), CaseFilePurpose.REPORT);

        assertThat(type).isEqualTo(DetectedType.PDF);
        assertThat(type.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void acceptsWhitespaceAfterPdfEndMarker() throws IOException {
        Path content = writeAscii("%PDF-1.7\n%%EOF \t\r\n");

        assertThat(inspector.inspect(content, CaseFilePurpose.REPORT)).isEqualTo(DetectedType.PDF);
    }

    @Test
    void acceptsMinimalPdfWithDistinctHeaderAndTrailer() throws IOException {
        assertThat(inspector.inspect(writeAscii("%PDF-%%EOF"), CaseFilePurpose.REPORT))
                .isEqualTo(DetectedType.PDF);
    }

    @Test
    void rejectsTruncatedPdfSignature() throws IOException {
        assertInvalid(writeAscii("%PDF%%EOF"), CaseFilePurpose.REPORT);
    }

    @Test
    void inspectsPdfTrailerBeyondHeader() throws IOException {
        Path content = writeAscii("%PDF-1.7\n" + "x".repeat(2048) + "\n%%EOF\n");

        assertThat(inspector.inspect(content, CaseFilePurpose.REPORT)).isEqualTo(DetectedType.PDF);
    }

    @Test
    void detectsJpegPhoto() throws IOException {
        Path content = write(HexFormat.of().parseHex("ffd8ffe000044a46ffd9"));
        DetectedType type = inspector.inspect(content, CaseFilePurpose.PHOTO);

        assertThat(type).isEqualTo(DetectedType.JPEG);
        assertThat(type.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void detectsPngPhoto() throws IOException {
        DetectedType type = inspector.inspect(write(PNG), CaseFilePurpose.PHOTO);

        assertThat(type).isEqualTo(DetectedType.PNG);
        assertThat(type.contentType()).isEqualTo("image/png");
    }

    @Test
    void acceptsImageReport() throws IOException {
        assertThat(inspector.inspect(write(PNG), CaseFilePurpose.REPORT)).isEqualTo(DetectedType.PNG);
    }

    @Test
    void rejectsEmptyContent() throws IOException {
        assertInvalid(write(new byte[0]), CaseFilePurpose.REPORT);
    }

    @Test
    void rejectsUnknownContent() throws IOException {
        assertInvalid(writeAscii("unknown content"), CaseFilePurpose.REPORT);
    }

    @Test
    void rejectsPdfWithoutEndMarker() throws IOException {
        assertInvalid(writeAscii("%PDF-1.7\ntruncated"), CaseFilePurpose.REPORT);
    }

    @Test
    void rejectsPdfWithContentAfterEndMarker() throws IOException {
        assertInvalid(writeAscii("%PDF-1.7\n%%EOF\ntrailing content"), CaseFilePurpose.REPORT);
    }

    @Test
    void rejectsJpegWithoutEndMarker() throws IOException {
        assertInvalid(write(HexFormat.of().parseHex("ffd8ffe000044a46")), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPngWithoutEndChunk() throws IOException {
        byte[] truncated = Arrays.copyOf(PNG, PNG.length - 12);

        assertInvalid(write(truncated), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPngWithoutHeaderChunkFirst() throws IOException {
        byte[] content = PNG.clone();
        System.arraycopy("IDAT".getBytes(StandardCharsets.US_ASCII), 0, content, 12, 4);

        assertInvalid(write(content), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPngWithWrongHeaderChunkLength() throws IOException {
        byte[] content = PNG.clone();
        content[11] = 12;

        assertInvalid(write(content), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPngWithIncompleteHeaderChunk() throws IOException {
        byte[] content = new byte[28];
        System.arraycopy(PNG, 0, content, 0, 16);
        System.arraycopy(PNG, PNG.length - 12, content, 16, 12);

        assertInvalid(write(content), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPngWithDataAfterEndChunk() throws IOException {
        byte[] content = Arrays.copyOf(PNG, PNG.length + 1);

        assertInvalid(write(content), CaseFilePurpose.PHOTO);
    }

    @Test
    void rejectsPdfForPhotoPurpose() throws IOException {
        assertInvalid(writeAscii("%PDF-1.7\n%%EOF"), CaseFilePurpose.PHOTO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<!DOCTYPE html><html></html>", "<svg xmlns='http://www.w3.org/2000/svg'/>", "PK\u0003\u0004zip content"})
    void rejectsOtherFormatsDisguisedWithPngFilename(String bytes) throws IOException {
        assertInvalid(writeAscii(bytes), CaseFilePurpose.PHOTO);
    }

    private void assertInvalid(Path content, CaseFilePurpose purpose) {
        assertThatThrownBy(() -> inspector.inspect(content, purpose)).isInstanceOf(ValidationException.class);
    }

    private Path writeAscii(String content) throws IOException {
        return write(content.getBytes(StandardCharsets.US_ASCII));
    }

    private Path write(byte[] content) throws IOException {
        return Files.write(directory.resolve("disguised.png"), content);
    }
}
