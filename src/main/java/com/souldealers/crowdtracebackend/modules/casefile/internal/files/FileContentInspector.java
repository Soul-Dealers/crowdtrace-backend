package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

/** Checks format boundaries without decoding images or loading the whole upload. */
@Component
public class FileContentInspector {
    private static final int HEADER_BYTES = 16;
    private static final int TRAILER_BYTES = 1024;
    private static final int PNG_MIN_BYTES = 45;
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_TRAILER = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_HEADER = HexFormat.of().parseHex("ffd8ff");
    private static final byte[] JPEG_TRAILER = HexFormat.of().parseHex("ffd9");
    private static final byte[] PNG_HEADER = HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452");
    private static final byte[] PNG_END_CHUNK = HexFormat.of().parseHex("0000000049454e44");

    public DetectedType inspect(Path content, CaseFilePurpose purpose) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(content)) {
            long size = channel.size();
            byte[] header = read(channel, 0, (int) Math.min(size, HEADER_BYTES));
            int trailerSize = (int) Math.min(size, TRAILER_BYTES);
            byte[] trailer = read(channel, size - trailerSize, trailerSize);
            DetectedType type = detect(header, trailer, size);

            boolean allowed = switch (purpose) {
                case REPORT -> true;
                case PHOTO -> type != DetectedType.PDF;
            };
            if (!allowed) {
                throw new ValidationException("File type is not allowed for this purpose");
            }
            return type;
        }
    }

    private DetectedType detect(byte[] header, byte[] trailer, long size) {
        if (matches(header, 0, PDF_HEADER) && hasPdfTrailer(trailer, size)) {
            return DetectedType.PDF;
        }
        if (size >= JPEG_HEADER.length + JPEG_TRAILER.length
                && matches(header, 0, JPEG_HEADER)
                && matches(trailer, trailer.length - JPEG_TRAILER.length, JPEG_TRAILER)) {
            return DetectedType.JPEG;
        }
        if (size >= PNG_MIN_BYTES && matches(header, 0, PNG_HEADER)
                && matches(trailer, trailer.length - 12, PNG_END_CHUNK)) {
            return DetectedType.PNG;
        }
        throw new ValidationException("File content is empty, unsupported, or incomplete");
    }

    private boolean hasPdfTrailer(byte[] trailer, long size) {
        int end = trailer.length;
        while (end > 0 && isPdfWhitespace(trailer[end - 1])) {
            end--;
        }
        int markerOffset = end - PDF_TRAILER.length;
        long absoluteOffset = size - trailer.length + markerOffset;
        return absoluteOffset >= PDF_HEADER.length && matches(trailer, markerOffset, PDF_TRAILER);
    }

    private boolean isPdfWhitespace(byte value) {
        return value == 0 || value == '\t' || value == '\n' || value == '\f'
                || value == '\r' || value == ' ';
    }

    private boolean matches(byte[] content, int offset, byte[] expected) {
        return offset >= 0 && offset + expected.length <= content.length
                && Arrays.equals(content, offset, offset + expected.length, expected, 0, expected.length);
    }

    private byte[] read(SeekableByteChannel channel, long position, int length) throws IOException {
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                throw new IOException("Unable to read upload content");
            }
        }
        return buffer.array();
    }
}
