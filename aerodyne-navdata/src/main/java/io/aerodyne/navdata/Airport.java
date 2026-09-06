package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.List;
import java.util.Objects;

/**
 * An airport, from the ARINC 424 {@code PA} record plus the {@code PG} runway records that
 * follow it.
 *
 * @param icaoCode          four-character ICAO location indicator, e.g. {@code EGLL}
 * @param iataCode          three-character IATA code, when published
 * @param position          the airport reference point
 * @param elevationFt       field elevation
 * @param transitionAltitudeFt altitude at which the altimeter is set to standard on climb,
 *                          {@code null} where the state publishes none
 * @param longestRunwayFt   length of the longest runway, used to screen alternates
 * @param publicMilitary    the record's public/military indicator
 * @param name              the full published name
 * @param runways           the runways at this airport
 */
public record Airport(String icaoCode,
                      String iataCode,
                      GeoPoint position,
                      int elevationFt,
                      Integer transitionAltitudeFt,
                      int longestRunwayFt,
                      String publicMilitary,
                      String name,
                      List<Runway> runways) implements Fix {

    public Airport {
        Objects.requireNonNull(icaoCode, "icaoCode");
        Objects.requireNonNull(position, "position");
        runways = runways == null ? List.of() : List.copyOf(runways);
    }

    @Override
    public String ident() {
        return icaoCode;
    }

    /** The first two characters of an ICAO code are the region it is published under. */
    @Override
    public String icaoRegion() {
        return icaoCode.length() >= 2 ? icaoCode.substring(0, 2) : icaoCode;
    }

    @Override
    public FixKind kind() {
        return FixKind.AIRPORT;
    }

    /** A copy of this airport carrying the given runways. */
    public Airport withRunways(List<Runway> newRunways) {
        return new Airport(icaoCode, iataCode, position, elevationFt, transitionAltitudeFt,
                longestRunwayFt, publicMilitary, name, newRunways);
    }

    /** True when at least one runway is long enough for the aircraft being dispatched. */
    public boolean hasRunwayAtLeast(int requiredLengthFt) {
        return runways.stream().anyMatch(r -> r.lengthFt() >= requiredLengthFt)
                || (runways.isEmpty() && longestRunwayFt >= requiredLengthFt);
    }

    /** True when some runway is served by a precision approach. */
    public boolean hasPrecisionApproach() {
        return runways.stream().anyMatch(Runway::hasPrecisionApproach);
    }

    public java.util.Optional<Runway> runway(String designator) {
        String wanted = designator.trim().toUpperCase(java.util.Locale.ROOT);
        return runways.stream()
                .filter(r -> r.designator().equalsIgnoreCase(wanted)
                        || r.designator().equalsIgnoreCase("RW" + wanted))
                .findFirst();
    }
}
