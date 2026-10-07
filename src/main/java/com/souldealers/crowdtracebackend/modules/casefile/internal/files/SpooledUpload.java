package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Owns the temporary content file; the caller retains ownership of the input stream. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SpooledUpload implements AutoCloseable {
    private final Path path;
    private final long sizeBytes;
    private final String checksumSha256;

    public static SpooledUpload from(InputStream input, long maxBytes) throws IOException {
        Objects.requireNonNull(input, "Input stream is required");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Maximum file size must be positive");
        }
        MessageDigest digest = sha256();
        Path path = Files.createTempFile("ct-case-file-", ".upload");
        try {
            long size = copy(input, path, maxBytes, digest);
            return new SpooledUpload(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static long copy(InputStream input, Path path, long maxBytes, MessageDigest digest) throws IOException {
        DigestInputStream content = new DigestInputStream(input, digest);
        try (var output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            while (true) {
                long remaining = maxBytes - size;
                int length = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
                int count = content.read(buffer, 0, length);
                if (count == -1) {
                    return size;
                }
                if (count == 0) {
                    int value = content.read();
                    if (value == -1) {
                        return size;
                    }
                    buffer[0] = (byte) value;
                    count = 1;
                }
                if (count > remaining) {
                    throw new ValidationException("File exceeds the maximum allowed size");
                }
                output.write(buffer, 0, count);
                size += count;
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
