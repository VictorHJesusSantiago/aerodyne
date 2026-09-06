package io.aerodyne.weather.grib;

/**
 * Raised when a GRIB2 stream cannot be decoded.
 *
 * <p>Unlike navigation data, a corrupt wind file is not something to work around: a plan built
 * on half-decoded winds would look ordinary and be wrong. So the decoder fails loudly and the
 * caller falls back to a different forecast or to the standard atmosphere.
 */
public class Grib2Exception extends RuntimeException {

    private final long offset;

    public Grib2Exception(String message, long offset) {
        super(message + " (at byte " + offset + ")");
        this.offset = offset;
    }

    public Grib2Exception(String message) {
        super(message);
        this.offset = -1;
    }

    /** Byte offset in the stream where the problem was found, or -1 when not positional. */
    public long offset() {
        return offset;
    }
}
