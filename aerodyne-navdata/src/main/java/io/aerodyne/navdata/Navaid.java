package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * A radio navigation aid — an ARINC 424 {@code D} (VHF) or {@code DB} (NDB) record.
 *
 * @param ident               one to four characters, e.g. {@code OCK}
 * @param icaoRegion          two-character ICAO region code
 * @param position            the transmitter site
 * @param type                what kind of aid it is
 * @param frequencyKhz        frequency in kilohertz; a VOR on 115.10 MHz is stored as 115100
 * @param magneticVariationDeg the variation the station is aligned to, east positive; radials
 *                            are measured against this and not against the current variation
 * @param elevationFt         site elevation, or {@code null} when the record leaves it blank
 * @param name                the full published name
 */
public record Navaid(String ident,
                     String icaoRegion,
                     GeoPoint position,
                     NavaidType type,
                     int frequencyKhz,
                     double magneticVariationDeg,
                     Integer elevationFt,
                     String name) implements Fix {

    public Navaid {
        Objects.requireNonNull(ident, "ident");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(type, "type");
        if (frequencyKhz < 0) {
            throw new IllegalArgumentException("negative frequency for " + ident);
        }
    }

    /** Frequency in megahertz, the way a VHF aid is tuned and printed. */
    public double frequencyMhz() {
        return frequencyKhz / 1000.0;
    }

    public OptionalDouble elevationFeet() {
        return elevationFt == null ? OptionalDouble.empty() : OptionalDouble.of(elevationFt);
    }

    /**
     * Converts a published radial (magnetic, measured against this station's alignment) into a
     * true course, which is what the geodesy works in.
     */
    public double radialToTrueCourse(double magneticRadial) {
        return io.aerodyne.geo.Geodesy.normaliseBearing(magneticRadial + magneticVariationDeg);
    }

    @Override
    public FixKind kind() {
        return type == NavaidType.NDB || type == NavaidType.LOCATOR
                ? FixKind.NDB_NAVAID
                : FixKind.VHF_NAVAID;
    }
}
