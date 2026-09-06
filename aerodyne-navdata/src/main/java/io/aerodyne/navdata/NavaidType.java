package io.aerodyne.navdata;

/**
 * The kinds of radio aid the navigation database carries, as distinguished by the ARINC 424
 * VHF navaid class field and the NDB section.
 */
public enum NavaidType {

    /** VHF omnidirectional range, bearing information only. */
    VOR(true, false),
    /** Co-located VOR and DME. */
    VOR_DME(true, true),
    /** VOR co-located with the TACAN distance and bearing aid. */
    VORTAC(true, true),
    /** Military TACAN; civil aircraft use the DME half. */
    TACAN(false, true),
    /** Distance measuring equipment with no co-located bearing aid. */
    DME(false, true),
    /** Non-directional beacon. */
    NDB(false, false),
    /** Locator beacon associated with an instrument approach. */
    LOCATOR(false, false),
    /** Instrument landing system localiser. */
    ILS_LOCALISER(true, false);

    private final boolean providesBearing;
    private final boolean providesDistance;

    NavaidType(boolean providesBearing, boolean providesDistance) {
        this.providesBearing = providesBearing;
        this.providesDistance = providesDistance;
    }

    /** True when the aid gives a bearing, so it can anchor a radial-defined fix. */
    public boolean providesBearing() {
        return providesBearing;
    }

    /** True when the aid gives slant range, so it can anchor a DME-defined fix. */
    public boolean providesDistance() {
        return providesDistance;
    }

    /** A VOR/DME pair fixes a position on its own; a bare VOR or NDB needs a second aid. */
    public boolean fixesPositionAlone() {
        return providesBearing && providesDistance;
    }

    /**
     * Reads the five-character VHF navaid class field. Column 1 distinguishes VOR from a
     * standalone DME or TACAN, and column 2 says whether a distance aid is co-located.
     */
    public static NavaidType fromVhfClassField(String navaidClass) {
        if (navaidClass == null || navaidClass.isBlank()) {
            return NavaidType.VOR;
        }
        String c = (navaidClass + "     ").toUpperCase(java.util.Locale.ROOT);
        boolean vor = c.charAt(0) == 'V';
        char distanceAid = c.charAt(1);
        if (vor) {
            return switch (distanceAid) {
                case 'D' -> VOR_DME;
                case 'T' -> VORTAC;
                default -> VOR;
            };
        }
        return switch (distanceAid) {
            case 'T' -> TACAN;
            case 'D' -> DME;
            default -> ILS_LOCALISER;
        };
    }
}
