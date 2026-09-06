package io.aerodyne.weather.grib;

import io.aerodyne.weather.LatLonGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes GRIB2 upper-air forecasts.
 *
 * <p>GRIB2 is a container format: a file is a run of self-describing messages, each of which is
 * a chain of numbered sections. This decoder walks that chain and reads the subset that upper
 * winds and temperatures are actually published in:
 *
 * <ul>
 *   <li><b>Section 3</b>, grid definition template 3.0 — a regular latitude/longitude grid.</li>
 *   <li><b>Section 4</b>, product definition template 4.0 — an instantaneous forecast of one
 *       parameter at one horizontal surface.</li>
 *   <li><b>Section 5/7</b>, data representation template 5.0 — simple packing, where each value
 *       is an n-bit integer scaled by a reference, a binary and a decimal factor.</li>
 * </ul>
 *
 * <p>Anything else — complex packing, JPEG 2000, thinned grids, Lambert projections — is
 * refused by name rather than misread. A wind field decoded with the wrong unpacking looks
 * plausible and is wrong, and a plan built on it would be too.
 *
 * <p>Messages the decoder can parse but does not need are skipped by their declared length,
 * which is what lets a full model output file be read for the four fields a plan wants.
 */
public final class Grib2Decoder {

    private static final Logger log = LoggerFactory.getLogger(Grib2Decoder.class);

    /** The four bytes every message begins with. */
    private static final byte[] MAGIC = {'G', 'R', 'I', 'B'};
    /** The four bytes section 8 consists of. */
    private static final byte[] END_MARKER = {'7', '7', '7', '7'};
    /** The only GRIB edition this decoder reads. */
    private static final int SUPPORTED_EDITION = 2;
    /** Grid definition template 3.0: a regular latitude/longitude grid. */
    private static final int TEMPLATE_LATLON_GRID = 0;
    /** Data representation template 5.0: simple packing. */
    private static final int TEMPLATE_SIMPLE_PACKING = 0;
    /** Latitudes and longitudes are stored in millionths of a degree. */
    private static final double ANGLE_SCALE = 1e-6;

    /** Bit 2 of the scanning mode flags: rows run south to north when set. */
    private static final int SCAN_J_POSITIVE = 0x40;
    /** Bit 1 of the scanning mode flags: columns run east to west when set. */
    private static final int SCAN_I_NEGATIVE = 0x80;

    public List<Grib2Message> decode(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            return decode(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read GRIB2 file " + file, e);
        }
    }

    public List<Grib2Message> decode(InputStream stream) {
        try {
            return decode(stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read GRIB2 stream", e);
        }
    }

    /**
     * Decodes every message in a buffer, returning only those the model can use — the wind
     * components and temperature on isobaric surfaces.
     */
    public List<Grib2Message> decode(byte[] data) {
        List<Grib2Message> messages = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int start = indexOfMagic(data, offset);
            if (start < 0) {
                break;
            }
            long messageLength = readMessageLength(data, start);
            int end = (int) (start + messageLength);
            if (messageLength <= 0 || end > data.length) {
                throw new Grib2Exception("message declares a length of " + messageLength
                        + " but only " + (data.length - start) + " bytes remain", start);
            }
            verifyEndMarker(data, end);
            decodeMessage(data, start, end).ifPresent(messages::add);
            offset = end;
        }
        if (messages.isEmpty() && data.length > 0) {
            log.warn("decoded {} bytes of GRIB2 without finding a usable upper-air field",
                    data.length);
        }
        return List.copyOf(messages);
    }

    // ------------------------------------------------------------------ message

    private java.util.Optional<Grib2Message> decodeMessage(byte[] data, int start, int end) {
        int edition = data[start + 7] & 0xFF;
        if (edition != SUPPORTED_EDITION) {
            throw new Grib2Exception("GRIB edition " + edition + " is not supported", start);
        }
        int discipline = data[start + 6] & 0xFF;

        Instant referenceTime = null;
        GridGeometry geometry = null;
        ProductInfo product = null;
        PackingInfo packing = null;
        byte[] bitmapSection = null;

        // Sections follow the fixed-length section 0 and run to the end marker.
        int offset = start + 16;
        while (offset < end - END_MARKER.length) {
            int sectionLength = readInt(data, offset);
            int sectionNumber = data[offset + 4] & 0xFF;
            if (sectionLength <= 0 || offset + sectionLength > end) {
                throw new Grib2Exception("section " + sectionNumber + " declares a length of "
                        + sectionLength, offset);
            }
            switch (sectionNumber) {
                case 1 -> referenceTime = readReferenceTime(data, offset);
                case 3 -> geometry = readGridDefinition(data, offset);
                case 4 -> product = readProductDefinition(data, offset, discipline);
                case 5 -> packing = readDataRepresentation(data, offset);
                case 6 -> bitmapSection = java.util.Arrays.copyOfRange(
                        data, offset, offset + sectionLength);
                case 7 -> {
                    if (geometry == null || product == null || packing == null || referenceTime == null) {
                        throw new Grib2Exception(
                                "data section reached before the sections describing it", offset);
                    }
                    if (!product.parameter().isUpperAir()
                            || product.levelType() != Grib2Message.ISOBARIC_SURFACE) {
                        // Not a field the model wants; the length walk still gets us past it.
                        return java.util.Optional.empty();
                    }
                    rejectBitmap(bitmapSection, offset);
                    float[] values = unpack(data, offset + 5, sectionLength - 5,
                            geometry.pointCount(), packing);
                    return java.util.Optional.of(new Grib2Message(
                            product.parameter(),
                            referenceTime,
                            referenceTime.plusSeconds(product.forecastSeconds()),
                            product.levelType(),
                            product.levelValue(),
                            geometry.toGrid(values)));
                }
                case 2, 8 -> { /* local use and the end marker carry nothing we read */ }
                default -> log.debug("skipping unexpected GRIB2 section {}", sectionNumber);
            }
            offset += sectionLength;
        }
        return java.util.Optional.empty();
    }

    // ------------------------------------------------------------------ section 1

    private Instant readReferenceTime(byte[] data, int sectionStart) {
        int year = readShort(data, sectionStart + 12);
        int month = data[sectionStart + 14] & 0xFF;
        int day = data[sectionStart + 15] & 0xFF;
        int hour = data[sectionStart + 16] & 0xFF;
        int minute = data[sectionStart + 17] & 0xFF;
        int second = data[sectionStart + 18] & 0xFF;
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC);
        } catch (java.time.DateTimeException badDate) {
            throw new Grib2Exception("reference time " + year + "-" + month + "-" + day
                    + " " + hour + ":" + minute + " is not a date", sectionStart);
        }
    }

    // ------------------------------------------------------------------ section 3

    private GridGeometry readGridDefinition(byte[] data, int sectionStart) {
        int template = readShort(data, sectionStart + 12);
        if (template != TEMPLATE_LATLON_GRID) {
            throw new Grib2Exception("grid definition template 3." + template
                    + " is not supported; only a regular latitude/longitude grid (3.0) is",
                    sectionStart);
        }
        // Template 3.0 begins at octet 15, which is offset 14 from the start of the section.
        int t = sectionStart + 14;
        int ni = readInt(data, t + 16);
        int nj = readInt(data, t + 20);
        double la1 = readSignedAngle(data, t + 32);
        double lo1 = readSignedAngle(data, t + 36);
        double la2 = readSignedAngle(data, t + 41);
        double lo2 = readSignedAngle(data, t + 45);
        double di = readInt(data, t + 49) * ANGLE_SCALE;
        double dj = readInt(data, t + 53) * ANGLE_SCALE;
        int scanningMode = data[t + 57] & 0xFF;

        if (ni <= 1 || nj <= 1) {
            throw new Grib2Exception("grid is " + ni + "x" + nj
                    + "; a thinned or single-row grid is not supported", sectionStart);
        }
        // The increments are magnitudes; the scanning mode flags say which way the rows run.
        double latitudeIncrement = (scanningMode & SCAN_J_POSITIVE) != 0 ? dj : -dj;
        double longitudeIncrement = (scanningMode & SCAN_I_NEGATIVE) != 0 ? -di : di;

        log.debug("GRIB2 grid {}x{} from {},{} to {},{}", ni, nj, la1, lo1, la2, lo2);
        return new GridGeometry(la1, lo1, latitudeIncrement, longitudeIncrement, ni, nj);
    }

    // ------------------------------------------------------------------ section 4

    private ProductInfo readProductDefinition(byte[] data, int sectionStart, int discipline) {
        int template = readShort(data, sectionStart + 7);
        if (template != 0 && template != 1) {
            throw new Grib2Exception("product definition template 4." + template
                    + " is not supported; only an instantaneous forecast (4.0) is", sectionStart);
        }
        // Template 4.0 begins at octet 10, which is offset 9 from the start of the section.
        int t = sectionStart + 9;
        int category = data[t] & 0xFF;
        int number = data[t + 1] & 0xFF;
        int timeUnit = data[t + 8] & 0xFF;
        int forecastTime = readInt(data, t + 9);
        int levelType = data[t + 13] & 0xFF;
        int levelScaleFactor = data[t + 14];
        int levelScaledValue = readInt(data, t + 15);

        double levelValue = levelScaledValue * Math.pow(10.0, -levelScaleFactor);
        return new ProductInfo(
                Grib2Parameter.of(discipline, category, number),
                levelType,
                levelValue,
                forecastSeconds(timeUnit, forecastTime, sectionStart));
    }

    /** Code table 4.4: the unit the forecast lead time is counted in. */
    private long forecastSeconds(int timeUnit, int forecastTime, int sectionStart) {
        return switch (timeUnit) {
            case 0 -> forecastTime * 60L;
            case 1 -> forecastTime * 3600L;
            case 2 -> forecastTime * 86_400L;
            case 10 -> forecastTime * 3L * 3600L;
            case 11 -> forecastTime * 6L * 3600L;
            case 12 -> forecastTime * 12L * 3600L;
            case 13 -> forecastTime;
            default -> throw new Grib2Exception(
                    "forecast time unit " + timeUnit + " is not one this decoder knows",
                    sectionStart);
        };
    }

    // ------------------------------------------------------------------ section 5

    private PackingInfo readDataRepresentation(byte[] data, int sectionStart) {
        int template = readShort(data, sectionStart + 9);
        if (template != TEMPLATE_SIMPLE_PACKING) {
            throw new Grib2Exception("data representation template 5." + template
                    + " is not supported; only simple packing (5.0) is", sectionStart);
        }
        int pointCount = readInt(data, sectionStart + 5);
        float reference = Float.intBitsToFloat(readInt(data, sectionStart + 11));
        int binaryScale = readSignedShort(data, sectionStart + 15);
        int decimalScale = readSignedShort(data, sectionStart + 17);
        int bitsPerValue = data[sectionStart + 19] & 0xFF;

        if (bitsPerValue > 32) {
            throw new Grib2Exception(bitsPerValue + " bits per value exceeds what simple packing "
                    + "can carry", sectionStart);
        }
        return new PackingInfo(pointCount, reference, binaryScale, decimalScale, bitsPerValue);
    }

    // ------------------------------------------------------------------ section 6

    /**
     * A bitmap marks points where the field has no value. Interpolating across such a hole
     * would fabricate a wind, so a message that has one is refused rather than half-read.
     */
    private void rejectBitmap(byte[] bitmapSection, int offset) {
        if (bitmapSection == null) {
            return;
        }
        int indicator = bitmapSection[5] & 0xFF;
        // 255 is the code for "no bitmap applies to this message", which is the usual case.
        if (indicator != 255) {
            throw new Grib2Exception(
                    "message carries a bitmap of missing values, which is not supported", offset);
        }
    }

    // ------------------------------------------------------------------ section 7

    /**
     * Simple packing: each value is an unsigned integer of {@code bitsPerValue} bits, and
     * {@code value = (reference + packed * 2^binaryScale) / 10^decimalScale}.
     *
     * <p>A field of zero bits per value is legal and means every point holds the reference
     * value, which is how a constant field is stored.
     */
    private float[] unpack(byte[] data, int dataStart, int dataLength, int pointCount,
                           PackingInfo packing) {
        if (packing.pointCount() != pointCount) {
            throw new Grib2Exception("the data section holds " + packing.pointCount()
                    + " values but the grid has " + pointCount + " points", dataStart);
        }
        double binaryFactor = Math.pow(2.0, packing.binaryScale());
        double decimalFactor = Math.pow(10.0, -packing.decimalScale());

        float[] values = new float[pointCount];
        if (packing.bitsPerValue() == 0) {
            java.util.Arrays.fill(values, (float) (packing.reference() * decimalFactor));
            return values;
        }

        long requiredBits = (long) pointCount * packing.bitsPerValue();
        if (requiredBits > (long) dataLength * 8) {
            throw new Grib2Exception("the data section holds " + dataLength + " bytes but "
                    + pointCount + " values of " + packing.bitsPerValue() + " bits need "
                    + (requiredBits + 7) / 8, dataStart);
        }

        BitReader bits = new BitReader(data, dataStart);
        for (int i = 0; i < pointCount; i++) {
            long packed = bits.read(packing.bitsPerValue());
            values[i] = (float) ((packing.reference() + packed * binaryFactor) * decimalFactor);
        }
        return values;
    }

    // ------------------------------------------------------------------ framing

    private static int indexOfMagic(byte[] data, int from) {
        outer:
        for (int i = from; i <= data.length - MAGIC.length; i++) {
            for (int j = 0; j < MAGIC.length; j++) {
                if (data[i + j] != MAGIC[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static long readMessageLength(byte[] data, int start) {
        if (start + 16 > data.length) {
            throw new Grib2Exception("truncated message header", start);
        }
        long length = 0;
        for (int i = 8; i < 16; i++) {
            length = (length << 8) | (data[start + i] & 0xFF);
        }
        return length;
    }

    private static void verifyEndMarker(byte[] data, int end) {
        for (int i = 0; i < END_MARKER.length; i++) {
            if (data[end - END_MARKER.length + i] != END_MARKER[i]) {
                throw new Grib2Exception("message does not end with 7777", end);
            }
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readSignedShort(byte[] data, int offset) {
        int raw = readShort(data, offset);
        // GRIB stores negatives in sign-magnitude, not two's complement.
        return (raw & 0x8000) != 0 ? -(raw & 0x7FFF) : raw;
    }

    /** A latitude or longitude: sign-magnitude millionths of a degree. */
    private static double readSignedAngle(byte[] data, int offset) {
        int raw = readInt(data, offset);
        double magnitude = (raw & 0x7FFFFFFF) * ANGLE_SCALE;
        return (raw & 0x80000000) != 0 ? -magnitude : magnitude;
    }

    /** Reads consecutive bit fields from a byte array, most significant bit first. */
    private static final class BitReader {

        private final byte[] data;
        private int byteOffset;
        private int bitOffset;

        BitReader(byte[] data, int startOffset) {
            this.data = data;
            this.byteOffset = startOffset;
        }

        long read(int bitCount) {
            long value = 0;
            for (int i = 0; i < bitCount; i++) {
                int bit = (data[byteOffset] >> (7 - bitOffset)) & 1;
                value = (value << 1) | bit;
                if (++bitOffset == 8) {
                    bitOffset = 0;
                    byteOffset++;
                }
            }
            return value;
        }
    }

    private record GridGeometry(double firstLatitude, double firstLongitude,
                                double latitudeIncrement, double longitudeIncrement,
                                int columns, int rows) {

        int pointCount() {
            return columns * rows;
        }

        LatLonGrid toGrid(float[] values) {
            return new LatLonGrid(firstLatitude, firstLongitude, latitudeIncrement,
                    longitudeIncrement, columns, rows, values);
        }
    }

    private record ProductInfo(Grib2Parameter parameter, int levelType, double levelValue,
                               long forecastSeconds) {
    }

    private record PackingInfo(int pointCount, float reference, int binaryScale,
                               int decimalScale, int bitsPerValue) {
    }
}
