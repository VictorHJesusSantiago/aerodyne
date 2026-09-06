package io.aerodyne.navdata;

import java.util.Objects;

/**
 * One {@code ER} record: a fix on an airway, together with the rules that apply to the leg
 * leaving it.
 *
 * @param sequenceNumber  position along the airway; records are published in this order
 * @param fixIdent        the identifier of the fix
 * @param fixRegion       ICAO region the fix is published under, needed to resolve the ident
 * @param direction       whether the leg leaving this point may be flown both ways
 * @param level           the level band this stretch serves
 * @param minimumAltitudeFt lowest usable altitude on the leg, {@code null} when unpublished
 * @param maximumAltitudeFt highest usable altitude on the leg, {@code null} when unpublished
 * @param descriptionCode the four-character description code; its second character marks the
 *                        end of a chain and its first the start of one
 */
public record AirwayPoint(int sequenceNumber,
                          String fixIdent,
                          String fixRegion,
                          DirectionRestriction direction,
                          AirwayLevel level,
                          Integer minimumAltitudeFt,
                          Integer maximumAltitudeFt,
                          String descriptionCode) {

    public AirwayPoint {
        Objects.requireNonNull(fixIdent, "fixIdent");
        direction = direction == null ? DirectionRestriction.BOTH : direction;
        level = level == null ? AirwayLevel.ALL : level;
        descriptionCode = descriptionCode == null ? "" : descriptionCode;
    }

    /** {@code E} in the second position of the description code closes the chain here. */
    public boolean endsChain() {
        return descriptionCode.length() >= 2 && Character.toUpperCase(descriptionCode.charAt(1)) == 'E';
    }

    /** {@code B} in the second position opens a new chain at this point. */
    public boolean beginsChain() {
        return descriptionCode.length() >= 2 && Character.toUpperCase(descriptionCode.charAt(1)) == 'B';
    }

    /**
     * True when a cruise at {@code altitudeFt} is inside the published limits and the level
     * band. The route search uses this to reject airways the aircraft cannot use.
     */
    public boolean permitsAltitude(double altitudeFt) {
        if (!level.servesAltitude(altitudeFt)) {
            return false;
        }
        if (minimumAltitudeFt != null && altitudeFt < minimumAltitudeFt) {
            return false;
        }
        return maximumAltitudeFt == null || altitudeFt <= maximumAltitudeFt;
    }
}
