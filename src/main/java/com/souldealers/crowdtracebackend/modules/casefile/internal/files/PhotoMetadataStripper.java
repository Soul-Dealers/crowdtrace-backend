package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.shared.ValidationException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Removes EXIF, XMP, IPTC, comments and embedded thumbnails from public photos without re-encoding pixels.
 * The only metadata kept is the EXIF orientation (when it is not the default), so the photo still displays
 * upright; it is rewritten into a fresh one-tag EXIF block, so no location, time or device data survives.
 * Bytes after the image end marker are dropped.
 */
public final class PhotoMetadataStripper {
    private static final int ORIENTATION_TAG = 0x0112;
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Set<String> PNG_KEPT_CHUNKS = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs");

    private PhotoMetadataStripper() {}

    public static byte[] strip(byte[] image, DetectedType type) {
        try {
            return switch (type) {
                case JPEG -> stripJpeg(image);
                case PNG -> stripPng(image);
                case PDF -> throw new IllegalArgumentException("Only photos are stripped");
            };
        } catch (IndexOutOfBoundsException malformed) {
            throw malformed();
        }
    }

    private static byte[] stripJpeg(byte[] b) {
        if (u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) {
            throw malformed();
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        byte[] jfif = null;
        int orientation = 1;
        int pos = 2;
        while (true) {
            if (u8(b, pos) != 0xFF) {
                throw malformed();
            }
            int marker = u8(b, pos + 1);
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xD9) {
                body.write(0xFF);
                body.write(0xD9);
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                body.write(b, pos, 2);
                pos += 2;
                continue;
            }
            int length = u16(b, pos + 2);
            int end = pos + 2 + length;
            if (length < 2 || end > b.length) {
                throw malformed();
            }
            int payload = pos + 4;
            if (marker == 0xE0) {
                if (jfif == null && startsWith(b, payload, end, "JFIF\0") && length >= 16) {
                    jfif = jfifWithoutThumbnail(b, payload);
                }
            } else if (marker == 0xE1) {
                if (startsWith(b, payload, end, "Exif\0\0")) {
                    orientation = tiffOrientation(b, payload + EXIF_HEADER.length, end);
                }
            } else if (marker == 0xEE) {
                if (startsWith(b, payload, end, "Adobe") && length >= 14) {
                    // Only the fixed colour-transform header; anything appended to it is dropped.
                    body.write(0xFF);
                    body.write(0xEE);
                    writeU16(body, 14);
                    body.write(b, payload, 12);
                }
            } else if ((marker == 0xE2 && startsWith(b, payload, end, "ICC_PROFILE\0"))
                    || (marker < 0xE0 || marker > 0xEF) && marker != 0xFE) {
                body.write(b, pos, end - pos);
            }
            pos = end;
            if (marker == 0xDA) {
                int scanEnd = scanEnd(b, pos);
                body.write(b, pos, scanEnd - pos);
                pos = scanEnd;
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 64);
        out.write(0xFF);
        out.write(0xD8);
        if (jfif != null) {
            out.writeBytes(jfif);
        }
        if (orientation != 1) {
            byte[] exif = concat(EXIF_HEADER, orientationTiff(orientation));
            out.write(0xFF);
            out.write(0xE1);
            writeU16(out, exif.length + 2);
            out.writeBytes(exif);
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** Entropy-coded data runs until a marker other than a stuffed byte or a restart marker. */
    private static int scanEnd(byte[] b, int pos) {
        while (true) {
            if (u8(b, pos) == 0xFF) {
                int next = u8(b, pos + 1);
                if (next != 0x00 && (next < 0xD0 || next > 0xD7)) {
                    return pos;
                }
                pos += 2;
            } else {
                pos++;
            }
        }
    }

    /** Keeps version, units and density; drops the embedded thumbnail. */
    private static byte[] jfifWithoutThumbnail(byte[] b, int payload) {
        byte[] segment = new byte[18];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE0;
        segment[3] = 16;
        System.arraycopy(b, payload, segment, 4, 12);
        return segment;
    }

    private static byte[] stripPng(byte[] b) {
        if (!Arrays.equals(b, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw malformed();
        }
        ByteArrayOutputStream chunks = new ByteArrayOutputStream(b.length);
        int orientation = 1;
        int pos = PNG_SIGNATURE.length;
        boolean first = true;
        while (true) {
            long length = u32(b, pos);
            if (length > b.length - pos - 12L) {
                throw malformed();
            }
            int end = pos + 12 + (int) length;
            String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
            CRC32 crc = new CRC32();
            crc.update(b, pos + 4, 4 + (int) length);
            if (crc.getValue() != u32(b, end - 4) || first != type.equals("IHDR")
                    || (type.equals("IEND") && length != 0)) {
                throw malformed();
            }
            first = false;
            if (type.equals("eXIf")) {
                orientation = tiffOrientation(b, pos + 8, end - 4);
            } else if (PNG_KEPT_CHUNKS.contains(type)) {
                chunks.write(b, pos, end - pos);
            }
            pos = end;
            if (type.equals("IEND")) {
                break;
            }
        }

        byte[] kept = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(kept.length + 64);
        out.writeBytes(PNG_SIGNATURE);
        int ihdrEnd = 12 + (int) u32(kept, 0);
        out.write(kept, 0, ihdrEnd);
        if (orientation != 1) {
            writePngChunk(out, "eXIf", orientationTiff(orientation));
        }
        out.write(kept, ihdrEnd, kept.length - ihdrEnd);
        return out.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeAndData = concat(type.getBytes(StandardCharsets.ISO_8859_1), data);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        writeU32(out, data.length);
        out.writeBytes(typeAndData);
        writeU32(out, crc.getValue());
    }

    /** Reads IFD0's orientation tag; any malformed or unusual value means "upright". */
    static int tiffOrientation(byte[] b, int start, int end) {
        try {
            if (end - start < 8) {
                return 1;
            }
            boolean little;
            if (b[start] == 'I' && b[start + 1] == 'I') {
                little = true;
            } else if (b[start] == 'M' && b[start + 1] == 'M') {
                little = false;
            } else {
                return 1;
            }
            long ifd = start + tiffU32(b, start + 4, little);
            if (ifd + 2 > end) {
                return 1;
            }
            int count = tiffU16(b, (int) ifd, little);
            for (int i = 0; i < count; i++) {
                int entry = (int) ifd + 2 + i * 12;
                if (entry + 12 > end) {
                    return 1;
                }
                if (tiffU16(b, entry, little) == ORIENTATION_TAG && tiffU16(b, entry + 2, little) == 3) {
                    int value = tiffU16(b, entry + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        } catch (IndexOutOfBoundsException malformed) {
            return 1;
        }
    }

    /** A big-endian TIFF header with a single IFD0 entry: Orientation (SHORT). */
    private static byte[] orientationTiff(int orientation) {
        return new byte[] {
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};
    }

    private static boolean startsWith(byte[] b, int from, int end, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.ISO_8859_1);
        return end - from >= expected.length
                && Arrays.equals(b, from, from + expected.length, expected, 0, expected.length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        return (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i) {
        return ((long) u16(b, i) << 16) | u16(b, i + 2);
    }

    private static int tiffU16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : u16(b, i);
    }

    private static long tiffU32(byte[] b, int i, boolean little) {
        return little ? tiffU16(b, i, true) | ((long) tiffU16(b, i + 2, true) << 16) : u32(b, i);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        writeU16(out, (int) (value >>> 16) & 0xFFFF);
        writeU16(out, (int) value & 0xFFFF);
    }

    private static ValidationException malformed() {
        return new ValidationException("Photo could not be processed");
    }
}
