package io.aerodyne.navdata.arinc;

/**
 * The column map for every ARINC 424 record the parser reads.
 *
 * <p>Every offset the parser uses is declared here and nowhere else. That is deliberate:
 * suppliers ship variants — a state that pads the name field differently, a cycle that moves the
 * ILS identifier — and when one does, the correction is a constant in this file rather than a
 * hunt through the parsing code.
 *
 * <p>Columns follow the specification's numbering: 1-indexed, inclusive at both ends. A field
 * spanning columns 33 to 51 is written as a {@code _START} of 33 and an {@code _END} of 51;
 * a packed position field is given only its start, because it is always 19 columns wide.
 *
 * <p>Layout as published in ARINC 424-18.
 */
public final class Arinc424Layout {

    private Arinc424Layout() {
    }

    // ------------------------------------------------------------------ common fields

    /** Column 1: {@code S} for a standard record, {@code T} tailored, {@code H} a file header. */
    public static final int RECORD_TYPE_COLUMN = 1;
    /** Columns 2-4: which supplier or state issued the record. */
    public static final int CUSTOMER_AREA_START = 2;
    public static final int CUSTOMER_AREA_END = 4;
    /** Column 5: the section code. */
    public static final int SECTION_CODE_COLUMN = 5;
    /** Column 6: the subsection code, for every section except airport and heliport. */
    public static final int SUBSECTION_CODE_COLUMN = 6;
    /** Column 13: the subsection code for airport ({@code P}) and heliport ({@code H}) records. */
    public static final int AIRPORT_SUBSECTION_CODE_COLUMN = 13;
    /** Columns 129-132: the AIRAC cycle the record was issued in, {@code YYNN}. */
    public static final int CYCLE_START = 129;
    public static final int CYCLE_END = 132;

    // ------------------------------------------------------------------ PA — airport

    public static final int AIRPORT_IDENT_START = 7;
    public static final int AIRPORT_IDENT_END = 10;
    public static final int AIRPORT_REGION_START = 11;
    public static final int AIRPORT_REGION_END = 12;
    public static final int AIRPORT_IATA_START = 14;
    public static final int AIRPORT_IATA_END = 16;
    /** Columns 28-30: longest runway in hundreds of feet, so {@code 129} means 12 900 ft. */
    public static final int AIRPORT_LONGEST_RUNWAY_START = 28;
    public static final int AIRPORT_LONGEST_RUNWAY_END = 30;
    public static final int AIRPORT_POSITION_START = 33;
    /** Columns 52-56: signed, whole degrees — {@code W0004} is four degrees west. */
    public static final int AIRPORT_MAGNETIC_VARIATION_START = 52;
    public static final int AIRPORT_MAGNETIC_VARIATION_END = 56;
    public static final int AIRPORT_ELEVATION_START = 57;
    public static final int AIRPORT_ELEVATION_END = 61;
    public static final int AIRPORT_TRANSITION_ALTITUDE_START = 73;
    public static final int AIRPORT_TRANSITION_ALTITUDE_END = 77;
    public static final int AIRPORT_TRANSITION_LEVEL_START = 78;
    public static final int AIRPORT_TRANSITION_LEVEL_END = 82;
    /** Column 83: {@code C} civil, {@code M} military, {@code P} private, {@code J} joint. */
    public static final int AIRPORT_PUBLIC_MILITARY_COLUMN = 83;
    public static final int AIRPORT_NAME_START = 94;
    public static final int AIRPORT_NAME_END = 123;

    // ------------------------------------------------------------------ PG — runway

    public static final int RUNWAY_AIRPORT_IDENT_START = 7;
    public static final int RUNWAY_AIRPORT_IDENT_END = 10;
    public static final int RUNWAY_IDENT_START = 14;
    public static final int RUNWAY_IDENT_END = 18;
    public static final int RUNWAY_LENGTH_START = 23;
    public static final int RUNWAY_LENGTH_END = 27;
    /** Columns 28-31: magnetic bearing in tenths of a degree, {@code 2694} being 269.4. */
    public static final int RUNWAY_BEARING_START = 28;
    public static final int RUNWAY_BEARING_END = 31;
    public static final int RUNWAY_POSITION_START = 33;
    /** Columns 52-56: signed, thousandths of a percent. */
    public static final int RUNWAY_GRADIENT_START = 52;
    public static final int RUNWAY_GRADIENT_END = 56;
    public static final int RUNWAY_THRESHOLD_ELEVATION_START = 61;
    public static final int RUNWAY_THRESHOLD_ELEVATION_END = 65;
    public static final int RUNWAY_DISPLACED_THRESHOLD_START = 66;
    public static final int RUNWAY_DISPLACED_THRESHOLD_END = 69;
    public static final int RUNWAY_WIDTH_START = 78;
    public static final int RUNWAY_WIDTH_END = 80;
    public static final int RUNWAY_ILS_IDENT_START = 82;
    public static final int RUNWAY_ILS_IDENT_END = 85;

    // ------------------------------------------------------------- D — VHF navaid

    public static final int VHF_IDENT_START = 14;
    public static final int VHF_IDENT_END = 17;
    public static final int VHF_REGION_START = 20;
    public static final int VHF_REGION_END = 21;
    /** Columns 23-27: frequency in tens of kilohertz, so {@code 11510} is 115.10 MHz. */
    public static final int VHF_FREQUENCY_START = 23;
    public static final int VHF_FREQUENCY_END = 27;
    /** Columns 28-32: the navaid class, whose first two characters give the type. */
    public static final int VHF_CLASS_START = 28;
    public static final int VHF_CLASS_END = 32;
    public static final int VHF_POSITION_START = 33;
    /** Columns 75-79: station declination, signed, tenths of a degree. */
    public static final int VHF_DECLINATION_START = 75;
    public static final int VHF_DECLINATION_END = 79;
    public static final int VHF_ELEVATION_START = 80;
    public static final int VHF_ELEVATION_END = 84;
    public static final int VHF_NAME_START = 94;
    public static final int VHF_NAME_END = 123;

    // ------------------------------------------------------------- DB — NDB navaid

    public static final int NDB_IDENT_START = 14;
    public static final int NDB_IDENT_END = 17;
    public static final int NDB_REGION_START = 20;
    public static final int NDB_REGION_END = 21;
    /** Columns 23-27: frequency in tenths of a kilohertz, so {@code 03550} is 355.0 kHz. */
    public static final int NDB_FREQUENCY_START = 23;
    public static final int NDB_FREQUENCY_END = 27;
    public static final int NDB_CLASS_START = 28;
    public static final int NDB_CLASS_END = 32;
    public static final int NDB_POSITION_START = 33;
    /** Columns 75-79: magnetic variation, signed, tenths of a degree. */
    public static final int NDB_MAGNETIC_VARIATION_START = 75;
    public static final int NDB_MAGNETIC_VARIATION_END = 79;
    public static final int NDB_NAME_START = 94;
    public static final int NDB_NAME_END = 123;

    // ---------------------------------------------------------- EA/PC — waypoint

    public static final int WAYPOINT_AREA_START = 7;
    public static final int WAYPOINT_AREA_END = 10;
    public static final int WAYPOINT_IDENT_START = 14;
    public static final int WAYPOINT_IDENT_END = 18;
    public static final int WAYPOINT_REGION_START = 20;
    public static final int WAYPOINT_REGION_END = 21;
    /** Columns 27-29: waypoint type — whether it is a computed fix, an intersection, and so on. */
    public static final int WAYPOINT_TYPE_START = 27;
    public static final int WAYPOINT_TYPE_END = 29;
    /** Columns 30-31: usage — {@code R} enroute, {@code T} terminal, both when both appear. */
    public static final int WAYPOINT_USAGE_START = 30;
    public static final int WAYPOINT_USAGE_END = 31;
    public static final int WAYPOINT_POSITION_START = 33;
    public static final int WAYPOINT_NAME_START = 99;
    public static final int WAYPOINT_NAME_END = 123;

    // ------------------------------------------------------------- ER — airway

    public static final int AIRWAY_IDENT_START = 14;
    public static final int AIRWAY_IDENT_END = 18;
    public static final int AIRWAY_SEQUENCE_START = 26;
    public static final int AIRWAY_SEQUENCE_END = 29;
    public static final int AIRWAY_FIX_IDENT_START = 30;
    public static final int AIRWAY_FIX_IDENT_END = 34;
    public static final int AIRWAY_FIX_REGION_START = 35;
    public static final int AIRWAY_FIX_REGION_END = 36;
    /** Column 37: which section the referenced fix lives in — {@code D}, {@code E} or {@code P}. */
    public static final int AIRWAY_FIX_SECTION_COLUMN = 37;
    public static final int AIRWAY_FIX_SUBSECTION_COLUMN = 38;
    /** Columns 40-43: the description code; its second character opens or closes a chain. */
    public static final int AIRWAY_DESCRIPTION_CODE_START = 40;
    public static final int AIRWAY_DESCRIPTION_CODE_END = 43;
    /** Column 45: route type — {@code A} ATS, {@code R} RNAV, {@code O} oceanic. */
    public static final int AIRWAY_ROUTE_TYPE_COLUMN = 45;
    /** Column 46: level — {@code H} upper, {@code L} lower, {@code B} both. */
    public static final int AIRWAY_LEVEL_COLUMN = 46;
    /** Column 47: direction restriction — {@code F}, {@code B}, or blank for either way. */
    public static final int AIRWAY_DIRECTION_COLUMN = 47;
    public static final int AIRWAY_MINIMUM_ALTITUDE_START = 84;
    public static final int AIRWAY_MINIMUM_ALTITUDE_END = 88;
    public static final int AIRWAY_MAXIMUM_ALTITUDE_START = 89;
    public static final int AIRWAY_MAXIMUM_ALTITUDE_END = 93;

    // ------------------------------------------------- PD/PE/PF — terminal procedures

    /** Columns 14-19: the procedure's published name, e.g. {@code MODMI1J}. */
    public static final int PROCEDURE_IDENT_START = 14;
    public static final int PROCEDURE_IDENT_END = 19;
    /**
     * Column 20: the route type. For a departure or arrival it says which segment the record
     * belongs to; for an approach it says what kind of approach it is.
     */
    public static final int PROCEDURE_ROUTE_TYPE_COLUMN = 20;
    /** Columns 21-25: the transition this record belongs to; blank for the common route. */
    public static final int PROCEDURE_TRANSITION_START = 21;
    public static final int PROCEDURE_TRANSITION_END = 25;
    public static final int PROCEDURE_SEQUENCE_START = 27;
    public static final int PROCEDURE_SEQUENCE_END = 29;
    public static final int PROCEDURE_FIX_IDENT_START = 30;
    public static final int PROCEDURE_FIX_IDENT_END = 34;
    public static final int PROCEDURE_FIX_REGION_START = 35;
    public static final int PROCEDURE_FIX_REGION_END = 36;
    /**
     * Column 39: the continuation record number. Procedure records carry it here rather than at
     * column 22, which is where every other section puts it.
     */
    public static final int PROCEDURE_CONTINUATION_COLUMN = 39;
    /** Columns 40-43: the waypoint description code; its second character marks a fly-over. */
    public static final int PROCEDURE_DESCRIPTION_START = 40;
    public static final int PROCEDURE_DESCRIPTION_END = 43;
    public static final int PROCEDURE_TURN_DIRECTION_COLUMN = 44;
    /** Columns 48-49: the path and terminator — the leg type. */
    public static final int PROCEDURE_LEG_TYPE_START = 48;
    public static final int PROCEDURE_LEG_TYPE_END = 49;
    public static final int PROCEDURE_NAVAID_START = 51;
    public static final int PROCEDURE_NAVAID_END = 54;
    public static final int PROCEDURE_NAVAID_REGION_START = 55;
    public static final int PROCEDURE_NAVAID_REGION_END = 56;
    /** Columns 61-64: bearing from the recommended navaid, tenths of a degree. */
    public static final int PROCEDURE_THETA_START = 61;
    public static final int PROCEDURE_THETA_END = 64;
    /** Columns 65-68: distance from the recommended navaid, tenths of a nautical mile. */
    public static final int PROCEDURE_RHO_START = 65;
    public static final int PROCEDURE_RHO_END = 68;
    /** Columns 69-72: the course flown, tenths of a degree magnetic. */
    public static final int PROCEDURE_COURSE_START = 69;
    public static final int PROCEDURE_COURSE_END = 72;
    /** Columns 73-76: the leg's length in tenths of a mile, or a holding time. */
    public static final int PROCEDURE_DISTANCE_START = 73;
    public static final int PROCEDURE_DISTANCE_END = 76;
    /** Column 82: how the two altitude fields are to be read. */
    public static final int PROCEDURE_ALTITUDE_DESCRIPTION_COLUMN = 82;
    public static final int PROCEDURE_ALTITUDE_ONE_START = 85;
    public static final int PROCEDURE_ALTITUDE_ONE_END = 89;
    public static final int PROCEDURE_ALTITUDE_TWO_START = 90;
    public static final int PROCEDURE_ALTITUDE_TWO_END = 94;
    public static final int PROCEDURE_SPEED_LIMIT_START = 100;
    public static final int PROCEDURE_SPEED_LIMIT_END = 102;
    /** Columns 103-106: the published descent angle, signed hundredths of a degree. */
    public static final int PROCEDURE_VERTICAL_ANGLE_START = 103;
    public static final int PROCEDURE_VERTICAL_ANGLE_END = 106;
    /** Column 119: whether the speed limit is a maximum, a minimum or mandatory. */
    public static final int PROCEDURE_SPEED_DESCRIPTION_COLUMN = 119;

    // ---------------------------------------------------------------- AS: grid MORA

    /** Columns 14-16: the southern edge of the latitude band, as {@code N45} or {@code S12}. */
    public static final int MORA_LATITUDE_START = 14;
    public static final int MORA_LATITUDE_END = 16;
    /** Columns 17-20: the western edge of the first square, as {@code W130} or {@code E005}. */
    public static final int MORA_LONGITUDE_START = 17;
    public static final int MORA_LONGITUDE_END = 20;
    /** Column 31: where the thirty values begin. Each is three characters wide. */
    public static final int MORA_VALUES_START = 31;
    /** How wide one value is: two digits of hundreds of feet, or {@code UNK}. */
    public static final int MORA_VALUE_WIDTH = 3;
}
