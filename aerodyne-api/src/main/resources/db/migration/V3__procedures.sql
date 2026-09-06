-- Terminal procedures: departures, arrivals and approaches.
--
-- A procedure is stored as its segments, not as one sequence of legs, because that is what the
-- published data is. A departure off runway 27L to one enroute point and the same departure off
-- 09R to another share their middle; storing every combination would multiply the rows and put
-- the shared part in each of them, so that correcting it would mean correcting all of them.
--
-- Legs carry no position of their own. A leg names a fix, and the fix's position lives in the
-- waypoint or navaid table; a leg that ends at an altitude has no position at all. Storing a
-- geometry here would mean inventing one for those.

-- Character columns are VARCHAR, never CHAR. PostgreSQL blank-pads a CHAR, so a two-letter
-- region stored in CHAR(2) is fine but a shorter value comes back padded, and code that compares
-- it against the value it wrote gets a mismatch it cannot see. VARCHAR stores exactly what it is
-- given, which is also what the ORM expects a String to map to.--
-- Numeric columns are DOUBLE PRECISION, not NUMERIC. The domain works in doubles throughout,
-- because these are physical quantities and not money: a fuel figure is the result of an
-- integration and carries no exact decimal value to preserve. NUMERIC would force a conversion
-- at every read and write, and the ORM would reject the mismatch anyway.
CREATE TABLE procedure_definition (
    id             BIGSERIAL PRIMARY KEY,
    cycle_id       BIGINT      NOT NULL REFERENCES airac_cycle (id) ON DELETE CASCADE,
    airport_icao   VARCHAR(4)  NOT NULL,
    ident          VARCHAR(8)  NOT NULL,
    -- SID, STAR, APPROACH.
    procedure_type VARCHAR(8)  NOT NULL,
    -- The ARINC route type. For an approach it says what kind it is: I for ILS, R for RNAV.
    route_type     VARCHAR(1)  NOT NULL,
    UNIQUE (cycle_id, airport_icao, ident, procedure_type)
);

CREATE INDEX procedure_airport_idx ON procedure_definition (cycle_id, airport_icao);

COMMENT ON TABLE procedure_definition IS
    'One published departure, arrival or approach. Its legs hang off procedure_transition.';

CREATE TABLE procedure_transition (
    id             BIGSERIAL PRIMARY KEY,
    procedure_id   BIGINT      NOT NULL REFERENCES procedure_definition (id) ON DELETE CASCADE,
    ordinal        INTEGER     NOT NULL,
    -- RUNWAY, COMMON, ENROUTE, APPROACH_TRANSITION, FINAL.
    transition_kind VARCHAR(20) NOT NULL,
    -- A runway designator, an enroute fix, or blank for the common route.
    identifier     VARCHAR(8)  NOT NULL DEFAULT '',
    route_type     VARCHAR(1)  NOT NULL,
    UNIQUE (procedure_id, ordinal)
);

CREATE TABLE procedure_leg (
    id                    BIGSERIAL PRIMARY KEY,
    transition_id         BIGINT      NOT NULL REFERENCES procedure_transition (id) ON DELETE CASCADE,
    sequence_number       INTEGER     NOT NULL,
    -- The path and terminator: TF, CF, VA, HM and the rest.
    leg_type              VARCHAR(8)  NOT NULL,
    fix_ident             VARCHAR(5),
    fix_region            VARCHAR(2),
    -- LEFT, RIGHT, EITHER, UNSPECIFIED.
    turn_direction        VARCHAR(12) NOT NULL DEFAULT 'UNSPECIFIED',
    recommended_navaid    VARCHAR(5),
    navaid_region         VARCHAR(2),
    theta_deg             DOUBLE PRECISION,
    rho_nm                DOUBLE PRECISION,
    magnetic_course_deg   DOUBLE PRECISION,
    route_distance_nm     DOUBLE PRECISION,
    -- NONE, AT, AT_OR_ABOVE, AT_OR_BELOW, BETWEEN, AT_OR_ABOVE_SECOND, GLIDE_PATH.
    altitude_kind         VARCHAR(20) NOT NULL DEFAULT 'NONE',
    altitude_one_ft       INTEGER,
    altitude_two_ft       INTEGER,
    -- NONE, MAXIMUM, MINIMUM, MANDATORY.
    speed_kind            VARCHAR(12) NOT NULL DEFAULT 'NONE',
    speed_kt              INTEGER     NOT NULL DEFAULT 0,
    -- Positional: the second character marks a fly-over, the fourth the end of a transition.
    description_code      VARCHAR(4),
    vertical_angle_deg    DOUBLE PRECISION,
    UNIQUE (transition_id, sequence_number)
);

CREATE INDEX procedure_leg_fix_idx ON procedure_leg (fix_ident);
