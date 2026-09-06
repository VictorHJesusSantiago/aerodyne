package io.aerodyne.navdata.arinc;

import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.GridMora;
import io.aerodyne.navdata.Navaid;
import io.aerodyne.navdata.NavaidType;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.WaypointUsage;
import io.aerodyne.navdata.procedure.AltitudeConstraint;
import io.aerodyne.navdata.procedure.LegType;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureLeg;
import io.aerodyne.navdata.procedure.ProcedureTransition;
import io.aerodyne.navdata.procedure.ProcedureType;
import io.aerodyne.navdata.procedure.SpeedConstraint;
import io.aerodyne.navdata.procedure.TransitionKind;
import io.aerodyne.navdata.procedure.TurnDirection;
import io.aerodyne.geo.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aerodyne.navdata.arinc.Arinc424Layout.*;

/**
 * Reads an ARINC 424 file into navigation objects.
 *
 * <p>The parser is deliberately forgiving. A navdata file is third-party text of tens of
 * megabytes and a handful of unreadable records in it is routine, so a bad record produces an
 * {@link ArincParseIssue} and is dropped rather than failing the load. What it will not do is
 * guess: a record with no readable position is dropped, never placed at the equator.
 *
 * <p>Two record types need state across lines. Runways ({@code PG}) are attached to the airport
 * ({@code PA}) they name, whichever order the two appear in. Airways ({@code ER}) are one record
 * per point, so they are accumulated by route identifier and assembled in sequence order once
 * the file ends.
 *
 * <p>Instances are not thread-safe; each parse call builds its own state and the class holds
 * none, so a single instance may be reused sequentially.
 */
public final class Arinc424Parser {

    private static final Logger log = LoggerFactory.getLogger(Arinc424Parser.class);

    /** ARINC writes altitudes as {@code UNKNN} or {@code FL350} as well as plain feet. */
    private static final int FLIGHT_LEVEL_MULTIPLIER = 100;

    public Arinc424File parse(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read ARINC 424 file " + file, e);
        }
    }

    public Arinc424File parse(Reader source) {
        ParseState state = new ParseState();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                readLine(new ArincRecord(line, lineNumber), state);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read ARINC 424 stream", e);
        }
        return state.build();
    }

    private void readLine(ArincRecord record, ParseState state) {
        if (record.line().isBlank() || record.isHeader()) {
            return;
        }
        ArincRecordType type = ArincRecordType.of(record);
        state.count(type);

        // Checked before the unsupported-section shortcut: a line this short is malformed
        // whatever it claims to be, and saying so is more use than silently skipping it.
        if (record.isTruncated()) {
            state.issue(ArincParseIssue.error(record, type,
                    "record is " + record.length() + " characters, too short to read"));
            return;
        }
        if (type == ArincRecordType.UNSUPPORTED) {
            return;
        }
        // Continuation records carry secondary data — extra names, notes — that nothing here
        // needs. Procedure records keep the continuation number in a column of their own.
        int continuationColumn = type.isProcedure() ? PROCEDURE_CONTINUATION_COLUMN : 22;
        if (record.isContinuation(continuationColumn)) {
            return;
        }
        state.noteCycle(record);

        switch (type) {
            case AIRPORT -> readAirport(record, state);
            case RUNWAY -> readRunway(record, state);
            case VHF_NAVAID -> readVhfNavaid(record, state);
            case NDB_NAVAID, TERMINAL_NDB -> readNdbNavaid(record, state, type);
            case ENROUTE_WAYPOINT, TERMINAL_WAYPOINT -> readWaypoint(record, state, type);
            case AIRWAY -> readAirwayPoint(record, state);
            case SID, STAR, APPROACH -> readProcedureLeg(record, state, type);
            case GRID_MORA -> readGridMora(record, state);
            case UNSUPPORTED -> { /* counted above */ }
        }
    }

    // ------------------------------------------------------------------- PA

    private void readAirport(ArincRecord record, ParseState state) {
        ArincRecordType type = ArincRecordType.AIRPORT;
        String icao = record.field(AIRPORT_IDENT_START, AIRPORT_IDENT_END);
        if (icao.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type, "airport record has no identifier"));
            return;
        }
        GeoPoint position = record.positionField(AIRPORT_POSITION_START);
        if (position == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "airport " + icao + " has no readable reference point"));
            return;
        }

        Integer elevation = record.intField(AIRPORT_ELEVATION_START, AIRPORT_ELEVATION_END);
        if (elevation == null) {
            state.issue(ArincParseIssue.warning(record, type,
                    "airport " + icao + " has no elevation; assuming sea level"));
            elevation = 0;
        }
        // The field is in hundreds of feet, which is why a 12 900 ft runway is written 129.
        int longestRunwayFt = record.intField(
                AIRPORT_LONGEST_RUNWAY_START, AIRPORT_LONGEST_RUNWAY_END, 0) * 100;

        Airport airport = new Airport(
                icao,
                emptyToNull(record.field(AIRPORT_IATA_START, AIRPORT_IATA_END)),
                position,
                elevation,
                record.intField(AIRPORT_TRANSITION_ALTITUDE_START, AIRPORT_TRANSITION_ALTITUDE_END),
                longestRunwayFt,
                String.valueOf(record.charAt(AIRPORT_PUBLIC_MILITARY_COLUMN)).trim(),
                emptyToNull(record.field(AIRPORT_NAME_START, AIRPORT_NAME_END)),
                List.of());
        state.addAirport(airport, record);
    }

    // ------------------------------------------------------------------- PG

    private void readRunway(ArincRecord record, ParseState state) {
        ArincRecordType type = ArincRecordType.RUNWAY;
        String airportIcao = record.field(RUNWAY_AIRPORT_IDENT_START, RUNWAY_AIRPORT_IDENT_END);
        String designator = record.field(RUNWAY_IDENT_START, RUNWAY_IDENT_END);
        if (airportIcao.isEmpty() || designator.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type,
                    "runway record is missing its airport or designator"));
            return;
        }
        GeoPoint threshold = record.positionField(RUNWAY_POSITION_START);
        if (threshold == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "runway " + airportIcao + "/" + designator + " has no readable threshold"));
            return;
        }

        // Bearing is published in tenths of a degree; gradient in thousandths of a percent.
        double bearing = record.intField(RUNWAY_BEARING_START, RUNWAY_BEARING_END, 0) / 10.0;
        Double gradient = record.signedField(RUNWAY_GRADIENT_START, RUNWAY_GRADIENT_END, 1000.0);

        Runway runway = new Runway(
                airportIcao,
                designator,
                threshold,
                bearing,
                record.intField(RUNWAY_LENGTH_START, RUNWAY_LENGTH_END, 0),
                record.intField(RUNWAY_WIDTH_START, RUNWAY_WIDTH_END, 0),
                record.intField(RUNWAY_THRESHOLD_ELEVATION_START, RUNWAY_THRESHOLD_ELEVATION_END, 0),
                record.intField(RUNWAY_DISPLACED_THRESHOLD_START, RUNWAY_DISPLACED_THRESHOLD_END, 0),
                gradient == null ? 0.0 : gradient,
                emptyToNull(record.field(RUNWAY_ILS_IDENT_START, RUNWAY_ILS_IDENT_END)));
        state.addRunway(runway);
    }

    // -------------------------------------------------------------------- D

    private void readVhfNavaid(ArincRecord record, ParseState state) {
        ArincRecordType type = ArincRecordType.VHF_NAVAID;
        String ident = record.field(VHF_IDENT_START, VHF_IDENT_END);
        if (ident.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type, "VHF navaid record has no identifier"));
            return;
        }
        GeoPoint position = record.positionField(VHF_POSITION_START);
        if (position == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "navaid " + ident + " has no readable position"));
            return;
        }
        // The frequency field counts tens of kilohertz: 11510 is 115.10 MHz, i.e. 115 100 kHz.
        int frequencyKhz = record.intField(VHF_FREQUENCY_START, VHF_FREQUENCY_END, 0) * 10;
        Double declination = record.signedField(VHF_DECLINATION_START, VHF_DECLINATION_END, 10.0);

        state.addNavaid(new Navaid(
                ident,
                record.field(VHF_REGION_START, VHF_REGION_END),
                position,
                NavaidType.fromVhfClassField(record.rawField(VHF_CLASS_START, VHF_CLASS_END)),
                frequencyKhz,
                declination == null ? 0.0 : declination,
                record.intField(VHF_ELEVATION_START, VHF_ELEVATION_END),
                emptyToNull(record.field(VHF_NAME_START, VHF_NAME_END))));
    }

    // ------------------------------------------------------------------- DB

    private void readNdbNavaid(ArincRecord record, ParseState state, ArincRecordType type) {
        String ident = record.field(NDB_IDENT_START, NDB_IDENT_END);
        if (ident.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type, "NDB record has no identifier"));
            return;
        }
        GeoPoint position = record.positionField(NDB_POSITION_START);
        if (position == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "NDB " + ident + " has no readable position"));
            return;
        }
        // NDB frequencies are published in tenths of a kilohertz: 03550 is 355.0 kHz.
        int frequencyKhz = Math.round(
                record.intField(NDB_FREQUENCY_START, NDB_FREQUENCY_END, 0) / 10.0f);
        Double variation = record.signedField(
                NDB_MAGNETIC_VARIATION_START, NDB_MAGNETIC_VARIATION_END, 10.0);
        // A terminal NDB is nearly always the locator on an approach rather than an enroute aid.
        NavaidType navaidType = type == ArincRecordType.TERMINAL_NDB
                ? NavaidType.LOCATOR
                : NavaidType.NDB;

        state.addNavaid(new Navaid(
                ident,
                record.field(NDB_REGION_START, NDB_REGION_END),
                position,
                navaidType,
                frequencyKhz,
                variation == null ? 0.0 : variation,
                null,
                emptyToNull(record.field(NDB_NAME_START, NDB_NAME_END))));
    }

    // ---------------------------------------------------------------- EA/PC

    private void readWaypoint(ArincRecord record, ParseState state, ArincRecordType type) {
        String ident = record.field(WAYPOINT_IDENT_START, WAYPOINT_IDENT_END);
        if (ident.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type, "waypoint record has no identifier"));
            return;
        }
        GeoPoint position = record.positionField(WAYPOINT_POSITION_START);
        if (position == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "waypoint " + ident + " has no readable position"));
            return;
        }
        String area = record.field(WAYPOINT_AREA_START, WAYPOINT_AREA_END);
        boolean terminal = type == ArincRecordType.TERMINAL_WAYPOINT;
        WaypointUsage usage = terminal
                ? WaypointUsage.TERMINAL
                : WaypointUsage.fromArincField(record.rawField(WAYPOINT_USAGE_START, WAYPOINT_USAGE_END));

        state.addWaypoint(new Waypoint(
                ident,
                record.field(WAYPOINT_REGION_START, WAYPOINT_REGION_END),
                position,
                usage,
                // On a terminal record the area field holds the airport the fix belongs to;
                // on an enroute record it is the literal "ENRT".
                terminal ? area : null,
                emptyToNull(record.field(WAYPOINT_NAME_START, WAYPOINT_NAME_END))));
    }

    // ------------------------------------------------------------------- ER

    private void readAirwayPoint(ArincRecord record, ParseState state) {
        ArincRecordType type = ArincRecordType.AIRWAY;
        String airwayIdent = record.field(AIRWAY_IDENT_START, AIRWAY_IDENT_END);
        String fixIdent = record.field(AIRWAY_FIX_IDENT_START, AIRWAY_FIX_IDENT_END);
        if (airwayIdent.isEmpty() || fixIdent.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type,
                    "airway record is missing its route or fix identifier"));
            return;
        }
        Integer sequence = record.intField(AIRWAY_SEQUENCE_START, AIRWAY_SEQUENCE_END);
        if (sequence == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "airway " + airwayIdent + " point " + fixIdent + " has no sequence number"));
            return;
        }

        AirwayPoint point = new AirwayPoint(
                sequence,
                fixIdent,
                record.field(AIRWAY_FIX_REGION_START, AIRWAY_FIX_REGION_END),
                DirectionRestriction.fromArincField(
                        String.valueOf(record.charAt(AIRWAY_DIRECTION_COLUMN))),
                AirwayLevel.fromArincField(String.valueOf(record.charAt(AIRWAY_LEVEL_COLUMN))),
                altitudeField(record, AIRWAY_MINIMUM_ALTITUDE_START, AIRWAY_MINIMUM_ALTITUDE_END),
                altitudeField(record, AIRWAY_MAXIMUM_ALTITUDE_START, AIRWAY_MAXIMUM_ALTITUDE_END),
                // Positional, like the procedure description code: read it raw.
                record.rawField(AIRWAY_DESCRIPTION_CODE_START, AIRWAY_DESCRIPTION_CODE_END));
        state.addAirwayPoint(airwayIdent, point);
    }

    /**
     * An altitude limit, which ARINC writes either as feet or as {@code FLnnn}. {@code UNLTD}
     * and blanks mean no limit, which is a {@code null} here rather than a very large number.
     */
    private static Integer altitudeField(ArincRecord record, int startColumn, int endColumn) {
        String raw = record.field(startColumn, endColumn).toUpperCase(java.util.Locale.ROOT);
        if (raw.isEmpty() || raw.startsWith("UNL")) {
            return null;
        }
        if (raw.startsWith("FL")) {
            try {
                return Integer.parseInt(raw.substring(2).trim()) * FLIGHT_LEVEL_MULTIPLIER;
            } catch (NumberFormatException notALevel) {
                return null;
            }
        }
        return record.intField(startColumn, endColumn);
    }


    // -------------------------------------------------------------- PD/PE/PF

    /**
     * Reads one leg of a terminal procedure.
     *
     * <p>Legs are accumulated by procedure and by segment; the segments are assembled into whole
     * procedures once the file ends, because a procedure's records are spread across the file
     * and there is no way to know a segment is finished until nothing more arrives for it.
     */
    private void readProcedureLeg(ArincRecord record, ParseState state, ArincRecordType type) {
        String airport = record.field(AIRPORT_IDENT_START, AIRPORT_IDENT_END);
        String procedureIdent = record.field(PROCEDURE_IDENT_START, PROCEDURE_IDENT_END);
        if (airport.isEmpty() || procedureIdent.isEmpty()) {
            state.issue(ArincParseIssue.error(record, type,
                    "procedure record is missing its airport or procedure identifier"));
            return;
        }
        Integer sequence = record.intField(PROCEDURE_SEQUENCE_START, PROCEDURE_SEQUENCE_END);
        if (sequence == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "procedure " + procedureIdent + " at " + airport + " has a leg with no "
                            + "sequence number"));
            return;
        }

        LegType legType = LegType.fromCode(
                record.field(PROCEDURE_LEG_TYPE_START, PROCEDURE_LEG_TYPE_END));
        if (legType == LegType.UNKNOWN) {
            // The leg is kept: its fix and altitude are still usable, and dropping it would put
            // a hole in the middle of a procedure that is otherwise fine.
            state.issue(ArincParseIssue.warning(record, type,
                    "procedure " + procedureIdent + " leg " + sequence + " has an unrecognised "
                            + "path and terminator '"
                            + record.field(PROCEDURE_LEG_TYPE_START, PROCEDURE_LEG_TYPE_END) + "'"));
        }

        ProcedureLeg leg = new ProcedureLeg(
                sequence,
                legType,
                emptyToNull(record.field(PROCEDURE_FIX_IDENT_START, PROCEDURE_FIX_IDENT_END)),
                emptyToNull(record.field(PROCEDURE_FIX_REGION_START, PROCEDURE_FIX_REGION_END)),
                TurnDirection.fromCode(
                        String.valueOf(record.charAt(PROCEDURE_TURN_DIRECTION_COLUMN))),
                emptyToNull(record.field(PROCEDURE_NAVAID_START, PROCEDURE_NAVAID_END)),
                emptyToNull(record.field(PROCEDURE_NAVAID_REGION_START, PROCEDURE_NAVAID_REGION_END)),
                scaled(record.intField(PROCEDURE_THETA_START, PROCEDURE_THETA_END), 10.0),
                scaled(record.intField(PROCEDURE_RHO_START, PROCEDURE_RHO_END), 10.0),
                scaled(record.intField(PROCEDURE_COURSE_START, PROCEDURE_COURSE_END), 10.0),
                scaled(record.intField(PROCEDURE_DISTANCE_START, PROCEDURE_DISTANCE_END), 10.0),
                AltitudeConstraint.of(
                        String.valueOf(record.charAt(PROCEDURE_ALTITUDE_DESCRIPTION_COLUMN)),
                        AltitudeConstraint.parseAltitudeField(
                                record.field(PROCEDURE_ALTITUDE_ONE_START, PROCEDURE_ALTITUDE_ONE_END)),
                        AltitudeConstraint.parseAltitudeField(
                                record.field(PROCEDURE_ALTITUDE_TWO_START, PROCEDURE_ALTITUDE_TWO_END))),
                SpeedConstraint.of(
                        String.valueOf(record.charAt(PROCEDURE_SPEED_DESCRIPTION_COLUMN)),
                        record.intField(PROCEDURE_SPEED_LIMIT_START, PROCEDURE_SPEED_LIMIT_END)),
                // Read raw, not trimmed: the description code is positional, and trimming a
                // leading blank shifts the fly-over and end-of-transition flags out of place.
                record.rawField(PROCEDURE_DESCRIPTION_START, PROCEDURE_DESCRIPTION_END),
                record.signedField(PROCEDURE_VERTICAL_ANGLE_START, PROCEDURE_VERTICAL_ANGLE_END, 100.0));

        ProcedureType procedureType = switch (type) {
            case SID -> ProcedureType.SID;
            case STAR -> ProcedureType.STAR;
            default -> ProcedureType.APPROACH;
        };
        char routeType = record.charAt(PROCEDURE_ROUTE_TYPE_COLUMN);
        String transitionIdent =
                record.field(PROCEDURE_TRANSITION_START, PROCEDURE_TRANSITION_END);

        state.addProcedureLeg(airport, procedureIdent, procedureType, routeType,
                transitionIdent, leg);
    }

    /** A field published in tenths or hundredths, or {@code null} when it is blank. */
    private static Double scaled(Integer raw, double divisor) {
        return raw == null ? null : raw / divisor;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Everything a single parse accumulates. Kept out of the parser itself so that the parser
     * holds no state between calls.
     */
    // ------------------------------------------------------------------- AS

    /**
     * One band of grid minimum off-route altitudes.
     *
     * <p>The thirty values are fixed-width and positional, so a short record is a real problem
     * rather than a tolerable one: the squares after the truncation would silently shift west.
     * A record that does not carry its full complement is read for as many squares as it
     * actually has and the rest are left unknown, which is the reading that cannot mislocate a
     * surveyed altitude.
     */
    private void readGridMora(ArincRecord record, ParseState state) {
        ArincRecordType type = ArincRecordType.GRID_MORA;
        Integer latitude = degreesField(record.field(MORA_LATITUDE_START, MORA_LATITUDE_END),
                'N', 'S');
        Integer longitude = degreesField(record.field(MORA_LONGITUDE_START, MORA_LONGITUDE_END),
                'E', 'W');
        if (latitude == null || longitude == null) {
            state.issue(ArincParseIssue.error(record, type,
                    "grid MORA record has no readable starting square"));
            return;
        }

        int[] altitudes = new int[GridMora.SQUARES_PER_RECORD];
        java.util.Arrays.fill(altitudes, GridMora.UNKNOWN);
        int unreadable = 0;
        for (int square = 0; square < GridMora.SQUARES_PER_RECORD; square++) {
            int start = MORA_VALUES_START + square * MORA_VALUE_WIDTH;
            int end = start + MORA_VALUE_WIDTH - 1;
            if (end > record.length()) {
                break;
            }
            String value = record.field(start, end);
            if (value.isEmpty() || value.equalsIgnoreCase("UNK")) {
                continue;
            }
            try {
                // Published in hundreds of feet, so 085 is 8500 ft.
                altitudes[square] = Integer.parseInt(value) * 100;
            } catch (NumberFormatException notANumber) {
                unreadable++;
            }
        }
        if (unreadable > 0) {
            state.issue(ArincParseIssue.warning(record, type,
                    unreadable + " of the squares from " + latitude + "/" + longitude
                            + " are not numbers; leaving them unsurveyed"));
        }
        state.addGridMora(new GridMora(latitude, longitude, altitudes));
    }

    /**
     * A signed whole number of degrees written as a hemisphere letter and digits, as
     * {@code N45} or {@code W130}. Returns null when it is neither.
     */
    private static Integer degreesField(String text, char positive, char negative) {
        if (text.length() < 2) {
            return null;
        }
        char hemisphere = Character.toUpperCase(text.charAt(0));
        if (hemisphere != positive && hemisphere != negative) {
            return null;
        }
        try {
            int degrees = Integer.parseInt(text.substring(1).trim());
            return hemisphere == negative ? -degrees : degrees;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static final class ParseState {

        private final Map<String, Airport> airportsByIcao = new LinkedHashMap<>();
        private final List<Runway> runways = new ArrayList<>();
        private final List<Navaid> navaids = new ArrayList<>();
        private final List<Waypoint> waypoints = new ArrayList<>();
        private final Map<String, List<AirwayPoint>> airwayPoints = new LinkedHashMap<>();
        /** Legs by procedure, then by segment; both keyed in the order they were first seen. */
        private final Map<ProcedureKey, Map<SegmentKey, List<ProcedureLeg>>> procedureLegs =
                new LinkedHashMap<>();
        private final List<GridMora> gridMora = new ArrayList<>();
        private final List<ArincParseIssue> issues = new ArrayList<>();
        private final Map<ArincRecordType, Integer> counts = new EnumMap<>(ArincRecordType.class);
        private final Map<String, Integer> cycleVotes = new HashMap<>();

        void count(ArincRecordType type) {
            counts.merge(type, 1, Integer::sum);
        }

        void issue(ArincParseIssue issue) {
            issues.add(issue);
        }

        void noteCycle(ArincRecord record) {
            String cycle = record.field(CYCLE_START, CYCLE_END);
            if (cycle.matches("\\d{4}")) {
                cycleVotes.merge(cycle, 1, Integer::sum);
            }
        }

        void addAirport(Airport airport, ArincRecord record) {
            Airport existing = airportsByIcao.put(airport.icaoCode(), airport);
            if (existing != null) {
                issues.add(ArincParseIssue.warning(record, ArincRecordType.AIRPORT,
                        "airport " + airport.icaoCode() + " appears more than once; keeping the later record"));
            }
        }

        void addRunway(Runway runway) {
            runways.add(runway);
        }

        void addNavaid(Navaid navaid) {
            navaids.add(navaid);
        }

        void addWaypoint(Waypoint waypoint) {
            waypoints.add(waypoint);
        }

        void addGridMora(GridMora mora) {
            gridMora.add(mora);
        }

        void addAirwayPoint(String airwayIdent, AirwayPoint point) {
            airwayPoints.computeIfAbsent(airwayIdent, k -> new ArrayList<>()).add(point);
        }

        void addProcedureLeg(String airport, String procedureIdent, ProcedureType type,
                             char routeType, String transitionIdent, ProcedureLeg leg) {
            TransitionKind kind = type.transitionKindFor(routeType, transitionIdent);
            procedureLegs
                    .computeIfAbsent(new ProcedureKey(airport, procedureIdent, type),
                            k -> new LinkedHashMap<>())
                    .computeIfAbsent(new SegmentKey(kind, transitionIdent, routeType),
                            k -> new ArrayList<>())
                    .add(leg);
        }

        Arinc424File build() {
            attachRunwaysToAirports();
            List<Airway> airways = assembleAirways();
            List<Procedure> procedures = assembleProcedures();
            AiracCycle cycle = electCycle();
            if (cycle == null && !cycleVotes.isEmpty()) {
                log.warn("no usable AIRAC cycle in file; candidates were {}", cycleVotes.keySet());
            }
            return new Arinc424File(cycle, List.copyOf(airportsByIcao.values()), runways,
                    navaids, waypoints, airways, procedures, gridMora, issues, counts);
        }

        private void attachRunwaysToAirports() {
            Map<String, List<Runway>> byAirport = new LinkedHashMap<>();
            for (Runway runway : runways) {
                byAirport.computeIfAbsent(runway.airportIcao(), k -> new ArrayList<>()).add(runway);
            }
            byAirport.forEach((icao, airportRunways) -> {
                Airport airport = airportsByIcao.get(icao);
                if (airport == null) {
                    // A runway whose airport record is absent: common when a file is a regional
                    // extract. The runway is still indexed, it just has no airport to hang on.
                    issues.add(new ArincParseIssue(ArincParseIssue.Severity.WARNING, -1,
                            ArincRecordType.RUNWAY,
                            "runways for " + icao + " have no airport record in this file", ""));
                    return;
                }
                airportsByIcao.put(icao, airport.withRunways(airportRunways));
            });
        }

        private List<Airway> assembleAirways() {
            List<Airway> assembled = new ArrayList<>(airwayPoints.size());
            airwayPoints.forEach((ident, points) -> {
                if (points.size() < 2) {
                    issues.add(new ArincParseIssue(ArincParseIssue.Severity.WARNING, -1,
                            ArincRecordType.AIRWAY,
                            "airway " + ident + " has only " + points.size() + " point; dropped", ""));
                    return;
                }
                points.sort(Comparator.comparingInt(AirwayPoint::sequenceNumber));
                assembled.add(new Airway(ident, points));
            });
            return assembled;
        }

        /**
         * Joins each procedure's segments into a whole procedure, with the legs of every segment
         * in sequence order.
         *
         * <p>A procedure with a single leg is kept, unlike a one-point airway: a departure that
         * is a single {@code VA} climb on runway heading is a real published procedure, whereas
         * an airway with one point is a fragment of one that did not survive the extract.
         */
        private List<Procedure> assembleProcedures() {
            List<Procedure> assembled = new ArrayList<>(procedureLegs.size());
            procedureLegs.forEach((procedure, segments) -> {
                List<ProcedureTransition> transitions = new ArrayList<>(segments.size());
                segments.forEach((segment, legs) -> {
                    legs.sort(Comparator.comparingInt(ProcedureLeg::sequenceNumber));
                    transitions.add(new ProcedureTransition(segment.kind(), segment.identifier(),
                            segment.routeType(), legs));
                });
                // The route type of the first segment read stands for the procedure, which for an
                // approach is what says whether it is an ILS or an RNAV.
                char routeType = transitions.get(0).routeType();
                assembled.add(new Procedure(procedure.airport(), procedure.ident(),
                        procedure.type(), routeType, transitions));
            });
            return assembled;
        }

        /**
         * Records carry the cycle they were issued in and a file usually agrees with itself,
         * but a merged file can hold two. The majority wins, and the parser says nothing about
         * it unless nothing at all was readable.
         */
        private AiracCycle electCycle() {
            return cycleVotes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .map(candidate -> {
                        try {
                            return AiracCycle.parse(candidate);
                        } catch (IllegalArgumentException notACycle) {
                            return null;
                        }
                    })
                    .orElse(null);
        }

        /** What identifies one procedure: the aerodrome, the name and the kind. */
        private record ProcedureKey(String airport, String ident, ProcedureType type) {
        }

        /** What identifies one segment of it. */
        private record SegmentKey(TransitionKind kind, String identifier, char routeType) {
        }
    }
}
