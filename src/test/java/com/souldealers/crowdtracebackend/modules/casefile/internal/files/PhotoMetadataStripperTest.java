package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoMetadataStripperTest {
    private static final String SECRET = "LAT-5.6037-LON-0.1870-PIXEL9";

    @Test
    void jpegLosesExifXmpIptcCommentsThumbnailAndTrailerButKeepsOrientation() throws IOException {
        byte[] original = jpeg(6, true);

        byte[] stripped = PhotoMetadataStripper.strip(original, DetectedType.JPEG);

        assertThat(contains(stripped, SECRET)).isFalse();
        assertThat(contains(stripped, "http://ns.adobe.com/xap")).isFalse();
        assertThat(contains(stripped, "Photoshop 3.0")).isFalse();
        assertThat(contains(stripped, "TRAILER")).isFalse();
        assertThat(contains(stripped, "THU")).isFalse();
        assertThat(stripped).endsWith(0xFF, 0xD9);
        assertThat(jpegOrientation(stripped)).isEqualTo(6);
        assertReadable(stripped);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyJpegOrientationSurvives(int orientation) throws IOException {
        byte[] stripped = PhotoMetadataStripper.strip(jpeg(orientation, false), DetectedType.JPEG);

        assertThat(jpegOrientation(stripped)).isEqualTo(orientation);
        assertReadable(stripped);
    }

    @Test
    void uprightJpegCarriesNoExifAtAll() throws IOException {
        byte[] stripped = PhotoMetadataStripper.strip(jpeg(1, true), DetectedType.JPEG);

        assertThat(contains(stripped, "Exif")).isFalse();
    }

    @Test
    void pngLosesTextExifAndTimeChunksButKeepsOrientation() throws IOException {
        byte[] original = png(3);

        byte[] stripped = PhotoMetadataStripper.strip(original, DetectedType.PNG);

        assertThat(contains(stripped, SECRET)).isFalse();
        assertThat(contains(stripped, "tEXt")).isFalse();
        assertThat(contains(stripped, "tIME")).isFalse();
        assertThat(contains(stripped, "TRAILER")).isFalse();
        assertThat(PhotoMetadataStripper.tiffOrientation(stripped, indexOf(stripped, "eXIf") + 4, stripped.length))
                .isEqualTo(3);
        assertReadable(stripped);
    }

    @Test
    void uprightPngCarriesNoExif() throws IOException {
        byte[] stripped = PhotoMetadataStripper.strip(png(1), DetectedType.PNG);

        assertThat(contains(stripped, "eXIf")).isFalse();
        assertReadable(stripped);
    }

    @Test
    void malformedPhotosAreRejected() throws IOException {
        byte[] jpeg = jpeg(1, false);
        byte[] png = png(1);
        png[40] ^= 1;

        assertThatThrownBy(() -> PhotoMetadataStripper.strip(Arrays.copyOf(jpeg, 30), DetectedType.JPEG))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhotoMetadataStripper.strip(png, DetectedType.PNG))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void adobeMarkerKeepsOnlyItsFixedHeader() throws IOException {
        byte[] plain = jpeg(1, false);
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(plain, 0, 2);
        segment(tagged, 0xEE, concat(ascii("Adobe"), new byte[] {0, 100, 0, 0, 0, 0, 1}, ascii(SECRET)));
        tagged.write(plain, 2, plain.length - 2);

        byte[] stripped = PhotoMetadataStripper.strip(tagged.toByteArray(), DetectedType.JPEG);

        assertThat(contains(stripped, "Adobe")).isTrue();
        assertThat(contains(stripped, SECRET)).isFalse();
        assertReadable(stripped);
    }

    @Test
    void pngWithDataInsideTheEndChunkIsRejected() throws IOException {
        byte[] plain = encode("png");
        ByteArrayOutputStream hostile = new ByteArrayOutputStream();
        hostile.write(plain, 0, plain.length - 12);
        chunk(hostile, "IEND", ascii(SECRET));
        hostile.write(plain, plain.length - 12, 12);

        assertThatThrownBy(() -> PhotoMetadataStripper.strip(hostile.toByteArray(), DetectedType.PNG))
                .isInstanceOf(ValidationException.class);
    }

    private static byte[] jpeg(int orientation, boolean everything) throws IOException {
        byte[] plain = encode("jpg");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        // JFIF with a 1x1 thumbnail.
        segment(out, 0xE0, concat(ascii("JFIF\0"), new byte[] {1, 1, 0, 0, 1, 0, 1, 1, 1}, ascii("THU")));
        segment(out, 0xE1, concat(ascii("Exif\0\0"), exifTiff(orientation)));
        if (everything) {
            segment(out, 0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>" + SECRET + "</x:xmpmeta>"));
            segment(out, 0xED, ascii("Photoshop 3.0\0" + SECRET));
            segment(out, 0xFE, ascii(SECRET));
        }
        int afterApp0 = 2 + 2 + ((plain[4] & 0xFF) << 8 | plain[5] & 0xFF);
        out.write(plain, afterApp0, plain.length - afterApp0);
        out.writeBytes(ascii("TRAILER" + SECRET));
        return out.toByteArray();
    }

    private static byte[] png(int orientation) throws IOException {
        byte[] plain = encode("png");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 33);
        chunk(out, "tEXt", ascii("Comment\0" + SECRET));
        chunk(out, "eXIf", exifTiff(orientation));
        chunk(out, "tIME", new byte[] {0x07, (byte) 0xEA, 11, 8, 12, 0, 0});
        out.write(plain, 33, plain.length - 33);
        out.writeBytes(ascii("TRAILER"));
        return out.toByteArray();
    }

    /** Little-endian TIFF: IFD0 with orientation and a GPS pointer, then a GPS IFD and the secret. */
    private static byte[] exifTiff(int orientation) {
        byte[] tiff = {
                'I', 'I', 42, 0, 8, 0, 0, 0,
                2, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0x25, (byte) 0x88, 4, 0, 1, 0, 0, 0, 38, 0, 0, 0,
                0, 0, 0, 0,
                1, 0,
                1, 0, 2, 0, 2, 0, 0, 0, 'N', 0, 0, 0,
                0, 0, 0, 0};
        return concat(tiff, ascii(SECRET));
    }

    private static int jpegOrientation(byte[] jpeg) {
        int exif = indexOf(jpeg, "Exif\0\0");
        return exif < 0 ? 1 : PhotoMetadataStripper.tiffOrientation(jpeg, exif + 6, jpeg.length);
    }

    private static byte[] encode(String format) throws IOException {
        BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 2, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static void assertReadable(byte[] image) throws IOException {
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
        assertThat(read.getWidth()).isEqualTo(16);
        assertThat(read.getHeight()).isEqualTo(8);
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >>> 8);
        out.write(payload.length + 2);
        out.writeBytes(payload);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeAndData = concat(ascii(type), data);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        out.writeBytes(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16),
                (byte) (data.length >>> 8), (byte) data.length});
        out.writeBytes(typeAndData);
        long value = crc.getValue();
        out.writeBytes(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    private static boolean contains(byte[] haystack, String needle) {
        return indexOf(haystack, needle) >= 0;
    }

    private static int indexOf(byte[] haystack, String needle) {
        byte[] n = ascii(needle);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
