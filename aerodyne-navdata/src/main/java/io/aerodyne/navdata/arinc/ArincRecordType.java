package io.aerodyne.navdata.arinc;

/**
 * The record types the parser recognises, keyed by their section and subsection codes.
 *
 * <p>An ARINC 424 file holds more than this — airspace boundaries, cruising tables, company
 * routes. Those sections are skipped rather than misread; the parser counts them so a caller can
 * see what a file contained.
 */
public enum ArincRecordType {

    /** {@code PA} — the airport primary record. */
    AIRPORT('P', 'A'),
    /** {@code PG} — one landing direction of a runway. */
    RUNWAY('P', 'G'),
    /** {@code D } — a VOR, DME, TACAN or localiser. */
    VHF_NAVAID('D', ' '),
    /** {@code DB} — a non-directional beacon. */
    NDB_NAVAID('D', 'B'),
    /** {@code EA} — an enroute waypoint. */
    ENROUTE_WAYPOINT('E', 'A'),
    /** {@code PC} — a terminal waypoint belonging to one airport. */
    TERMINAL_WAYPOINT('P', 'C'),
    /** {@code PN} — a terminal NDB, usually an approach locator. */
    TERMINAL_NDB('P', 'N'),
    /** {@code ER} — one point on an enroute airway. */
    AIRWAY('E', 'R'),
    /** {@code PD} — one leg of a standard instrument departure. */
    SID('P', 'D'),
    /** {@code PE} — one leg of a standard terminal arrival. */
    STAR('P', 'E'),
    /** {@code PF} — one leg of an instrument approach. */
    APPROACH('P', 'F'),
    /** {@code AS} — grid minimum off-route altitudes, thirty one-degree squares per record. */
    GRID_MORA('A', 'S'),
    /** Anything the parser does not read. */
    UNSUPPORTED('?', '?');

    private final char section;
    private final char subsection;

    ArincRecordType(char section, char subsection) {
        this.section = section;
        this.subsection = subsection;
    }

    public char section() {
        return section;
    }

    public char subsection() {
        return subsection;
    }

    /** Classifies a record by its section and subsection codes. */
    public static ArincRecordType of(ArincRecord record) {
        char section = record.sectionCode();
        char subsection = record.subsectionCode();
        for (ArincRecordType type : values()) {
            if (type != UNSUPPORTED && type.section == section && type.subsection == subsection) {
                return type;
            }
        }
        return UNSUPPORTED;
    }

    /** True for the three subsections that make up a terminal procedure. */
    public boolean isProcedure() {
        return this == SID || this == STAR || this == APPROACH;
    }

    /** The two-character code as it is written when talking about the format. */
    public String code() {
        return this == UNSUPPORTED ? "??" : "" + section + subsection;
    }
}
