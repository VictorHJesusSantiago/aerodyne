package io.aerodyne.weather.grib;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Builds GRIB2 messages for the decoder tests.
 *
 * <p>The fields are placed by the octet numbers in the WMO specification, written out beside
 * each one. That is deliberate: an encoder that simply mirrored the decoder's offsets would
 * agree with it about a mistake, whereas one written from the octet numbering disagrees with a
 * shifted field and the round-trip test fails.
 *
 * <p>Produces the subset the decoder supports: grid template 3.0, product template 4.0, and
 * simple packing (5.0), with no bitmap.
 */
final class Grib2Writer {

    private double firstLatitude = 60.0;
    private double firstLongitude = -20.0;
    private double latitudeIncrement = -1.0;
    private double longitudeIncrement = 1.0;
    private int columns = 5;
    private int rows = 4;
    private float[] values = new float[20];

    private int discipline = 0;
    private int parameterCategory = 2;
    private int parameterNumber = 2;
    private int levelType = 100;
    private int levelScaleFactor = 0;
    private int levelScaledValue = 25_000;
    private int forecastTimeUnit = 1;
    private int forecastTime = 6;
    private Instant referenceTime = Instant.parse("2024-06-01T00:00:00Z");

    private int bitsPerValue = 16;
    private int binaryScale = 0;
    private int decimalScale = 2;
    private int gridTemplate = 0;
    private int productTemplate = 0;
    private int dataTemplate = 0;
    private int edition = 2;
    private Integer bitmapIndicator;

    static Grib2Writer message() {
        return new Grib2Writer();
    }

    Grib2Writer grid(double firstLatitude, double firstLongitude,
                     double latitudeIncrement, double longitudeIncrement,
                     int columns, int rows) {
        this.firstLatitude = firstLatitude;
        this.firstLongitude = firstLongitude;
        this.latitudeIncrement = latitudeIncrement;
        this.longitudeIncrement = longitudeIncrement;
        this.columns = columns;
        this.rows = rows;
        this.values = new float[columns * rows];
        return this;
    }

    Grib2Writer values(float... values) {
        this.values = values.clone();
        return this;
    }

    /** Wind components live in discipline 0 category 2; temperature in category 0. */
    Grib2Writer parameter(int discipline, int category, int number) {
        this.discipline = discipline;
        this.parameterCategory = category;
        this.parameterNumber = number;
        return this;
    }

    Grib2Writer isobaricLevelHpa(double hectopascals) {
        this.levelType = 100;
        this.levelScaleFactor = 0;
        this.levelScaledValue = (int) Math.round(hectopascals * 100.0);
        return this;
    }

    Grib2Writer levelType(int levelType) {
        this.levelType = levelType;
        return this;
    }

    Grib2Writer referenceTime(Instant referenceTime) {
        this.referenceTime = referenceTime;
        return this;
    }

    Grib2Writer forecastHours(int hours) {
        this.forecastTimeUnit = 1;
        this.forecastTime = hours;
        return this;
    }

    Grib2Writer forecastTime(int unit, int value) {
        this.forecastTimeUnit = unit;
        this.forecastTime = value;
        return this;
    }

    Grib2Writer packing(int bitsPerValue, int binaryScale, int decimalScale) {
        this.bitsPerValue = bitsPerValue;
        this.binaryScale = binaryScale;
        this.decimalScale = decimalScale;
        return this;
    }

    Grib2Writer gridTemplate(int gridTemplate) {
        this.gridTemplate = gridTemplate;
        return this;
    }

    Grib2Writer productTemplate(int productTemplate) {
        this.productTemplate = productTemplate;
        return this;
    }

    Grib2Writer dataTemplate(int dataTemplate) {
        this.dataTemplate = dataTemplate;
        return this;
    }

    Grib2Writer edition(int edition) {
        this.edition = edition;
        return this;
    }

    Grib2Writer bitmapIndicator(int indicator) {
        this.bitmapIndicator = indicator;
        return this;
    }

    byte[] build() {
        byte[] section1 = section1();
        byte[] section3 = section3();
        byte[] section4 = section4();
        byte[] section5 = section5();
        byte[] section6 = bitmapIndicator == null ? new byte[0] : section6();
        byte[] section7 = section7();

        int total = 16 + section1.length + section3.length + section4.length
                + section5.length + section6.length + section7.length + 4;

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        // Section 0: octets 1-4 "GRIB", 5-6 reserved, 7 discipline, 8 edition, 9-16 length.
        out.writeBytes(new byte[]{'G', 'R', 'I', 'B', 0, 0});
        out.write(discipline);
        out.write(edition);
        writeLong8(out, total);
        out.writeBytes(section1);
        out.writeBytes(section3);
        out.writeBytes(section4);
        out.writeBytes(section5);
        out.writeBytes(section6);
        out.writeBytes(section7);
        // Section 8: the four bytes "7777".
        out.writeBytes(new byte[]{'7', '7', '7', '7'});
        return out.toByteArray();
    }

    /** Identification. Octets 13-14 year, 15 month, 16 day, 17 hour, 18 minute, 19 second. */
    private byte[] section1() {
        ZonedDateTime t = referenceTime.atZone(ZoneOffset.UTC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 21);
        out.write(1);
        writeShort(out, 74);   // 5-6   originating centre
        writeShort(out, 0);    // 7-8   subcentre
        out.write(2);          // 9     master tables version
        out.write(1);          // 10    local tables version
        out.write(1);          // 11    significance of reference time: start of forecast
        writeShort(out, t.getYear());
        out.write(t.getMonthValue());
        out.write(t.getDayOfMonth());
        out.write(t.getHour());
        out.write(t.getMinute());
        out.write(t.getSecond());
        out.write(0);          // 20    production status
        out.write(1);          // 21    type of data: forecast
        return out.toByteArray();
    }

    /** Grid definition, template 3.0. The template itself begins at octet 15. */
    private byte[] section3() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 72);
        out.write(3);
        out.write(0);                       // 6      source of grid definition
        writeInt(out, columns * rows);      // 7-10   number of data points
        out.write(0);                       // 11     octets for the optional point list
        out.write(0);                       // 12     interpretation of that list
        writeShort(out, gridTemplate);      // 13-14  grid definition template number

        out.write(6);                       // 15     shape of earth: spherical, radius 6371229 m
        out.write(0);                       // 16     scale factor of the radius
        writeInt(out, 0);                   // 17-20  scaled radius
        out.write(0);                       // 21     scale factor of the major axis
        writeInt(out, 0);                   // 22-25  scaled major axis
        out.write(0);                       // 26     scale factor of the minor axis
        writeInt(out, 0);                   // 27-30  scaled minor axis
        writeInt(out, columns);             // 31-34  Ni
        writeInt(out, rows);                // 35-38  Nj
        writeInt(out, 0);                   // 39-42  basic angle
        writeInt(out, 0);                   // 43-46  subdivisions of the basic angle
        writeAngle(out, firstLatitude);     // 47-50  La1
        writeAngle(out, firstLongitude);    // 51-54  Lo1
        out.write(0x30);                    // 55     resolution and component flags
        writeAngle(out, firstLatitude + (rows - 1) * latitudeIncrement);       // 56-59 La2
        writeAngle(out, firstLongitude + (columns - 1) * longitudeIncrement);  // 60-63 Lo2
        writeInt(out, (int) Math.round(Math.abs(longitudeIncrement) * 1e6));   // 64-67 Di
        writeInt(out, (int) Math.round(Math.abs(latitudeIncrement) * 1e6));    // 68-71 Dj
        out.write(scanningMode());          // 72     scanning mode flags
        return out.toByteArray();
    }

    /** Bit 1 set means westward columns, bit 2 set means northward rows. */
    private int scanningMode() {
        int mode = 0;
        if (longitudeIncrement < 0) {
            mode |= 0x80;
        }
        if (latitudeIncrement > 0) {
            mode |= 0x40;
        }
        return mode;
    }

    /** Product definition, template 4.0. The template begins at octet 10. */
    private byte[] section4() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 34);
        out.write(4);
        writeShort(out, 0);                 // 6-7    number of coordinate values
        writeShort(out, productTemplate);   // 8-9    product definition template number

        out.write(parameterCategory);       // 10     parameter category
        out.write(parameterNumber);         // 11     parameter number
        out.write(2);                       // 12     type of generating process: forecast
        out.write(0);                       // 13     background process
        out.write(96);                      // 14     generating process identifier
        writeShort(out, 0);                 // 15-16  hours after the data cutoff
        out.write(0);                       // 17     minutes after the cutoff
        out.write(forecastTimeUnit);        // 18     unit of the forecast time range
        writeInt(out, forecastTime);        // 19-22  forecast time
        out.write(levelType);               // 23     type of the first fixed surface
        out.write(levelScaleFactor);        // 24     scale factor of that surface
        writeInt(out, levelScaledValue);    // 25-28  scaled value of that surface
        out.write(255);                     // 29     type of the second fixed surface: none
        out.write(0);                       // 30     scale factor
        writeInt(out, 0);                   // 31-34  scaled value
        return out.toByteArray();
    }

    /** Data representation, template 5.0. The template begins at octet 12. */
    private byte[] section5() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 21);
        out.write(5);
        writeInt(out, columns * rows);      // 6-9    number of data points
        writeShort(out, dataTemplate);      // 10-11  data representation template number

        writeInt(out, Float.floatToIntBits(referenceValue()));  // 12-15 reference value R
        writeSignedShort(out, binaryScale);                     // 16-17 binary scale factor E
        writeSignedShort(out, decimalScale);                    // 18-19 decimal scale factor D
        out.write(bitsPerValue);                                // 20    bits per value
        out.write(0);                                           // 21    original field type: float
        return out.toByteArray();
    }

    /** Bitmap. Octet 6 is the indicator; 255 means no bitmap applies. */
    private byte[] section6() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 6);
        out.write(6);
        out.write(bitmapIndicator);
        return out.toByteArray();
    }

    /** Data. The packed values begin at octet 6. */
    private byte[] section7() {
        byte[] packed = pack();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 5 + packed.length);
        out.write(7);
        out.writeBytes(packed);
        return out.toByteArray();
    }

    /**
     * The smallest value in the field, scaled the way the decoder will unscale it. Simple
     * packing stores {@code (value * 10^D - R) / 2^E} as an unsigned integer, so the reference
     * has to be the minimum for every packed value to be non-negative.
     */
    private float referenceValue() {
        double decimalFactor = Math.pow(10.0, decimalScale);
        double min = Double.MAX_VALUE;
        for (float value : values) {
            min = Math.min(min, value * decimalFactor);
        }
        return (float) min;
    }

    private byte[] pack() {
        if (bitsPerValue == 0) {
            return new byte[0];
        }
        double decimalFactor = Math.pow(10.0, decimalScale);
        double binaryFactor = Math.pow(2.0, binaryScale);
        float reference = referenceValue();

        BitWriter bits = new BitWriter(values.length * bitsPerValue);
        for (float value : values) {
            long packed = Math.round((value * decimalFactor - reference) / binaryFactor);
            if (packed < 0 || packed >= (1L << bitsPerValue)) {
                throw new IllegalStateException(
                        value + " does not fit in " + bitsPerValue + " bits at this scale");
            }
            bits.write(packed, bitsPerValue);
        }
        return bits.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /** GRIB stores negatives in sign-magnitude, with the top bit as the sign. */
    private static void writeSignedShort(ByteArrayOutputStream out, int value) {
        int magnitude = Math.abs(value);
        writeShort(out, value < 0 ? (magnitude | 0x8000) : magnitude);
    }

    private static void writeAngle(ByteArrayOutputStream out, double degrees) {
        int magnitude = (int) Math.round(Math.abs(degrees) * 1e6);
        writeInt(out, degrees < 0 ? (magnitude | 0x80000000) : magnitude);
    }

    private static void writeLong8(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
    }

    /** Writes consecutive bit fields, most significant bit first. */
    private static final class BitWriter {

        private final byte[] buffer;
        private int bitPosition;

        BitWriter(int bitCount) {
            this.buffer = new byte[(bitCount + 7) / 8];
        }

        void write(long value, int bitCount) {
            for (int i = bitCount - 1; i >= 0; i--) {
                if (((value >>> i) & 1L) != 0L) {
                    buffer[bitPosition / 8] |= (byte) (1 << (7 - bitPosition % 8));
                }
                bitPosition++;
            }
        }

        byte[] toByteArray() {
            return buffer;
        }
    }
}
