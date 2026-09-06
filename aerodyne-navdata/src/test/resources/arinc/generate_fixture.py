"""Generates the ARINC 424 test fixture next to this script.

The fixture is a fixed-column file and hand-editing one is how column errors get
introduced, so it is generated instead: every field here is placed by the same
1-indexed, inclusive column range the parser reads it from (see Arinc424Layout).

Run from this directory:

    python generate_fixture.py

Regenerate the file whenever the column map changes, and check the result in.
"""

RECORD_LENGTH = 132


def record(*fields):
    """Builds one 132-character record from (start_column, text) pairs."""
    line = [" "] * RECORD_LENGTH
    for start, text in fields:
        for offset, char in enumerate(text):
            column = start - 1 + offset
            if column >= RECORD_LENGTH:
                raise ValueError(f"field {text!r} at column {start} runs past the record")
            line[column] = char
    return "".join(line).rstrip()


CYCLE = "2413"


def grid_mora(latitude, longitude, values):
    """One AS record: a latitude band and its one-degree squares, eastward.

    Values are hundreds of feet, or UNK for a square nobody surveyed. Fewer than
    thirty are written here on purpose — the parser has to leave the rest unknown
    rather than shifting the surveyed ones west to fill the gap.
    """
    return record(
        (1, "S"), (2, "EUR"), (5, "A"), (6, "S"),
        (14, latitude), (17, longitude),
        (31, "".join(value.ljust(3) for value in values)),
        (129, CYCLE))


def airport(icao, iata, position, elevation_ft, longest_runway_hundreds_ft,
            transition_altitude_ft, public_military, name):
    return record(
        (1, "S"), (2, "EUR"), (5, "P"),
        (7, icao), (11, icao[:2]), (13, "A"),
        (14, iata),
        (28, f"{longest_runway_hundreds_ft:03d}"),
        (33, position),
        (52, "W0004"),
        (57, f"{elevation_ft:05d}"),
        (73, f"{transition_altitude_ft:05d}"),
        (83, public_military),
        (94, name),
        (129, CYCLE),
    )


def runway(icao, designator, position, length_ft, bearing_tenths, width_ft,
           threshold_elevation_ft, displaced_ft, gradient, ils_ident):
    return record(
        (1, "S"), (2, "EUR"), (5, "P"),
        (7, icao), (11, icao[:2]), (13, "G"),
        (14, designator),
        (23, f"{length_ft:05d}"),
        (28, f"{bearing_tenths:04d}"),
        (33, position),
        (52, gradient),
        (61, f"{threshold_elevation_ft:05d}"),
        (66, f"{displaced_ft:04d}"),
        (78, f"{width_ft:03d}"),
        (82, ils_ident),
        (129, CYCLE),
    )


def vhf_navaid(ident, region, frequency_tens_khz, navaid_class, position,
               declination, elevation_ft, name):
    return record(
        (1, "S"), (2, "EUR"), (5, "D"),
        (11, region), (14, ident), (20, region),
        (23, f"{frequency_tens_khz:05d}"),
        (28, navaid_class),
        (33, position),
        (75, declination),
        (80, elevation_ft),
        (94, name),
        (129, CYCLE),
    )


def ndb_navaid(ident, region, frequency_tenths_khz, position, variation, name):
    return record(
        (1, "S"), (2, "EUR"), (5, "D"), (6, "B"),
        (11, region), (14, ident), (20, region),
        (23, f"{frequency_tenths_khz:05d}"),
        (28, "HH W"),
        (33, position),
        (75, variation),
        (94, name),
        (129, CYCLE),
    )


def enroute_waypoint(ident, region, position, usage, name):
    return record(
        (1, "S"), (2, "EUR"), (5, "E"), (6, "A"),
        (7, "ENRT"), (11, region), (14, ident), (20, region),
        (27, "W  "), (30, usage),
        (33, position),
        (99, name),
        (129, CYCLE),
    )


def terminal_waypoint(ident, airport_icao, position, name):
    return record(
        (1, "S"), (2, "EUR"), (5, "P"),
        (7, airport_icao), (11, airport_icao[:2]), (13, "C"),
        (14, ident), (20, airport_icao[:2]),
        (33, position),
        (99, name),
        (129, CYCLE),
    )


def airway_point(airway, sequence, fix_ident, fix_region, fix_section,
                 fix_subsection, description, level, direction,
                 minimum_altitude, maximum_altitude):
    return record(
        (1, "S"), (2, "EUR"), (5, "E"), (6, "R"),
        (7, "ENRT"),
        (14, airway),
        (26, f"{sequence:04d}"),
        (30, fix_ident), (35, fix_region),
        (37, fix_section), (38, fix_subsection),
        (40, description),
        (45, "A"), (46, level), (47, direction),
        (84, minimum_altitude), (89, maximum_altitude),
        (129, CYCLE),
    )



def procedure_leg(airport, subsection, procedure, route_type, transition, sequence,
                  fix_ident, leg_type, description="    ", turn=" ",
                  navaid="", course_tenths=None, distance_tenths=None,
                  altitude_description=" ", altitude_one="", altitude_two="",
                  speed=None, speed_description=" ", vertical_angle=""):
    """One PD/PE/PF leg. Columns are the procedure layout in Arinc424Layout."""
    fields = [
        (1, "S"), (2, "EUR"), (5, "P"),
        (7, airport), (11, airport[:2]), (13, subsection),
        (14, procedure), (20, route_type), (21, transition),
        (27, f"{sequence:03d}"),
        (30, fix_ident), (35, "EG" if fix_ident.strip() else "  "),
        (37, "E" if fix_ident.strip() else " "), (38, "A" if fix_ident.strip() else " "),
        (39, "0"),
        (40, description), (44, turn),
        (48, leg_type),
        (51, navaid),
        (82, altitude_description),
        (85, altitude_one), (90, altitude_two),
        (119, speed_description),
        (129, CYCLE),
    ]
    if course_tenths is not None:
        fields.append((69, f"{course_tenths:04d}"))
    if distance_tenths is not None:
        fields.append((73, f"{distance_tenths:04d}"))
    if speed is not None:
        fields.append((100, f"{speed:03d}"))
    if vertical_angle:
        fields.append((103, vertical_angle))
    return record(*fields)


LINES = [
    # File header. The parser skips these; it is here so that it is seen to.
    record((1, "HDR01AERODYNE TEST EXTRACT")),

    # --- airports and runways -------------------------------------------------
    airport("EGLL", "LHR", "N51284700W000275200", 83, 129, 6000, "C",
            "LONDON HEATHROW"),
    runway("EGLL", "RW27L", "N51284600W000242700", 12799, 2695, 164, 79, 0,
           "-0001", "ILL"),
    runway("EGLL", "RW09R", "N51284000W000312300", 12799, 895, 164, 77, 306,
           "+0001", "IAA"),
    airport("KJFK", "JFK", "N40382300W073464400", 13, 145, 18000, "C",
            "NEW YORK JOHN F KENNEDY INTL"),
    runway("KJFK", "RW04L", "N40372300W073471500", 12079, 435, 200, 12, 0,
           "+0000", "IHIQ"),

    # An airport with no elevation field: the parser must warn, not drop it.
    record(
        (1, "S"), (2, "EUR"), (5, "P"),
        (7, "EGKK"), (11, "EG"), (13, "A"), (14, "LGW"),
        (28, "084"),
        (33, "N51085300W000110400"),
        (73, "06000"), (83, "C"),
        (94, "LONDON GATWICK"),
        (129, CYCLE),
    ),

    # --- navaids --------------------------------------------------------------
    vhf_navaid("OCK", "EG", 11530, "VDHW", "N51180500W000265000", "W0003",
               "00276", "OCKHAM"),
    vhf_navaid("MID", "EG", 11400, "VTHW", "N50570000W000373000", "W0003",
               "00400", "MIDHURST"),
    vhf_navaid("DME", "SB", 11250, " D W", "S23261980W046282900", "W0021",
               "02461", "GUARULHOS DME"),
    ndb_navaid("LON", "EG", 3550, "N51290000W000290000", "W0003",
               "LONDON NDB"),

    # --- waypoints ------------------------------------------------------------
    enroute_waypoint("KOLIN", "EG", "N51000000W002000000", "R ", "KOLIN"),
    enroute_waypoint("BEDEK", "EG", "N50300000W004000000", "R ", "BEDEK"),
    enroute_waypoint("SOVAT", "EG", "N50000000W006000000", "R ", "SOVAT"),
    # Same identifier, other side of the world: what makes resolution positional.
    enroute_waypoint("KOLIN", "SB", "S23000000W045000000", "R ", "KOLIN BRAZIL"),
    # Terminal-only: not a legal enroute route element.
    terminal_waypoint("DET26", "EGLL", "N51200000W000200000", "DETLING TRANSITION"),

    # --- airways --------------------------------------------------------------
    airway_point("UN862", 10, "KOLIN", "EG", "E", "A", "  A ", "H", " ",
                 "24000", "46000"),
    airway_point("UN862", 20, "BEDEK", "EG", "E", "A", "    ", "H", " ",
                 "24000", "46000"),
    airway_point("UN862", 30, "SOVAT", "EG", "E", "A", " EE ", "H", " ",
                 "24000", "46000"),
    # A one-way low-level airway, and a limit written as a flight level.
    airway_point("L620", 10, "OCK", "EG", "D", " ", "  A ", "L", "F",
                 "03000", "FL195"),
    airway_point("L620", 20, "MID", "EG", "D", " ", " EE ", "L", "F",
                 "03000", "FL195"),


    # --- terminal procedures ---------------------------------------------------
    # A departure with a runway transition, a common route and two enroute transitions.
    # The runway transition starts with a heading-to-altitude leg, which has no fix at all.
    procedure_leg("EGLL", "D", "KOLI1J", "1", "RW27L", 10, "     ", "VA",
                  course_tenths=2695, altitude_description=" ", altitude_one="00800"),
    procedure_leg("EGLL", "D", "KOLI1J", "1", "RW27L", 20, "DET26", "DF",
                  description=" Y  ", altitude_description="+", altitude_one="03000",
                  speed=220),
    procedure_leg("EGLL", "D", "KOLI1J", "2", "     ", 10, "OCK  ", "TF",
                  turn="L", course_tenths=2100, distance_tenths=120,
                  altitude_description="+", altitude_one="06000"),
    procedure_leg("EGLL", "D", "KOLI1J", "2", "     ", 20, "KOLIN", "TF",
                  course_tenths=2400, distance_tenths=350,
                  altitude_description="B", altitude_one="24000", altitude_two="10000"),
    procedure_leg("EGLL", "D", "KOLI1J", "3", "BEDEK", 10, "BEDEK", "TF",
                  description="   Y", course_tenths=2450, distance_tenths=800),
    procedure_leg("EGLL", "D", "KOLI1J", "3", "SOVAT", 10, "SOVAT", "TF",
                  description="   Y", course_tenths=2500, distance_tenths=1200),

    # An arrival, whose route types mean the opposite of a departure's.
    procedure_leg("EGLL", "E", "BEDE1A", "1", "BEDEK", 10, "BEDEK", "IF"),
    procedure_leg("EGLL", "E", "BEDE1A", "2", "     ", 10, "KOLIN", "TF",
                  course_tenths=600, distance_tenths=800,
                  altitude_description="-", altitude_one="20000", speed=250),
    procedure_leg("EGLL", "E", "BEDE1A", "3", "RW27L", 10, "OCK  ", "TF",
                  course_tenths=300, distance_tenths=350,
                  altitude_description=" ", altitude_one="07000"),

    # An ILS approach: one approach transition, then the final.
    procedure_leg("EGLL", "F", "I27L", "I", "OCK  ", 10, "OCK  ", "IF"),
    procedure_leg("EGLL", "F", "I27L", "I", "OCK  ", 20, "DET26", "TF",
                  course_tenths=2700, distance_tenths=150,
                  altitude_description="+", altitude_one="03000"),
    procedure_leg("EGLL", "F", "I27L", "I", "     ", 30, "DET26", "IF",
                  altitude_description=" ", altitude_one="03000"),
    procedure_leg("EGLL", "F", "I27L", "I", "     ", 40, "RW27L", "CF",
                  description="   Y", course_tenths=2695, distance_tenths=60,
                  vertical_angle="-300"),

    # A leg whose path and terminator this parser does not know: kept, with a warning.
    procedure_leg("EGLL", "D", "ODD1X", "2", "     ", 10, "OCK  ", "ZZ"),

    # --- AS: grid minimum off-route altitudes ---------------------------------
    # The band from N51 to N52, starting at W005. Three surveyed squares and then
    # unsurveyed ones, which is what the western approaches actually look like.
    grid_mora("N51", "W005", ["023", "018", "012", "UNK", "007"]),
    # A southern-hemisphere band, so the sign handling is exercised in both directions.
    grid_mora("S23", "W047", ["085", "092", "UNK", "031"]),
    # A record whose squares are not numbers: read as far as it can, warned about.
    grid_mora("N40", "E008", ["012", "XYZ", "020"]),
    # A record with no readable starting square: dropped with an error.
    record((1, "S"), (2, "EUR"), (5, "A"), (6, "S"), (14, "???"), (17, "????"),
           (31, "012" * 5), (129, CYCLE)),

    # --- records the parser must survive rather than read ---------------------
    # A heliport record: an unsupported section, counted and skipped.
    record((1, "S"), (2, "EUR"), (5, "H"), (7, "EGLW"), (11, "EG"), (13, "A"),
           (14, "LON"), (129, CYCLE)),
    # A continuation record: primary data already read, nothing to add here.
    record((1, "S"), (2, "EUR"), (5, "D"), (11, "EG"), (14, "OCK"), (20, "EG"),
           (22, "2"), (94, "OCKHAM CONTINUATION"), (129, CYCLE)),
    # Truncated beyond use.
    "SEURP EGSS",
    # A waypoint whose position field is corrupt: dropped with an error.
    record((1, "S"), (2, "EUR"), (5, "E"), (6, "A"), (7, "ENRT"), (11, "EG"),
           (14, "BADFX"), (20, "EG"), (30, "R "), (33, "XX999999XX999999999"),
           (99, "CORRUPT FIX"), (129, CYCLE)),
    # An airway point with no sequence number: dropped with an error.
    record((1, "S"), (2, "EUR"), (5, "E"), (6, "R"), (7, "ENRT"), (14, "UQ99"),
           (30, "KOLIN"), (35, "EG"), (37, "E"), (38, "A"), (129, CYCLE)),
    # Blank line in the middle of the file.
    "",
]


if __name__ == "__main__":
    with open("extract.arinc", "w", encoding="ascii", newline="\n") as handle:
        for line in LINES:
            handle.write(line + "\n")
    print(f"wrote extract.arinc with {len(LINES)} records")
