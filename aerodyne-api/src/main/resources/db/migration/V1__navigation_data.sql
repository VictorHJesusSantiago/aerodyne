-- Navigation data for one or more AIRAC cycles.
--
-- Every cycle is kept rather than overwritten. A flight plan has to be reproducible against the
-- data it was built on, and a plan filed last week cannot be checked against this week's cycle;
-- so the cycle is part of the key of everything here, and old cycles are pruned on a schedule
-- rather than replaced in place.
--
-- Positions are PostGIS geography, not geometry. Geography measures in metres on the spheroid,
-- which is what "within 200 nautical miles" means for a navigation database; geometry would
-- measure in degrees and give an answer that is wrong by a factor of the cosine of the latitude.

-- Character columns are VARCHAR, never CHAR. PostgreSQL blank-pads a CHAR, so a two-letter
-- region stored in CHAR(2) is fine but a shorter value comes back padded, and code that compares
-- it against the value it wrote gets a mismatch it cannot see. VARCHAR stores exactly what it is
-- given, which is also what the ORM expects a String to map to.--
-- Numeric columns are DOUBLE PRECISION, not NUMERIC. The domain works in doubles throughout,
-- because these are physical quantities and not money: a fuel figure is the result of an
-- integration and carries no exact decimal value to preserve. NUMERIC would force a conversion
-- at every read and write, and the ORM would reject the mismatch anyway.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE airac_cycle (
    id              BIGSERIAL PRIMARY KEY,
    identifier      VARCHAR(4)  NOT NULL UNIQUE,
    effective_date  DATE        NOT NULL,
    expiry_date     DATE        NOT NULL,
    loaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          TEXT,
    CONSTRAINT airac_cycle_dates CHECK (expiry_date > effective_date)
);

COMMENT ON TABLE airac_cycle IS
    'One 28-day navigation data cycle. Plans reference the cycle they were built against.';

CREATE TABLE airport (
    id                      BIGSERIAL PRIMARY KEY,
    cycle_id                BIGINT      NOT NULL REFERENCES airac_cycle (id) ON DELETE CASCADE,
    icao_code               VARCHAR(4)  NOT NULL,
    iata_code               VARCHAR(3),
    name                    TEXT,
    position                GEOGRAPHY(POINT, 4326) NOT NULL,
    elevation_ft            INTEGER     NOT NULL,
    transition_altitude_ft  INTEGER,
    longest_runway_ft       INTEGER     NOT NULL DEFAULT 0,
    public_military         VARCHAR(1),
    UNIQUE (cycle_id, icao_code)
);

CREATE INDEX airport_position_idx ON airport USING GIST (position);
CREATE INDEX airport_icao_idx ON airport (icao_code);

CREATE TABLE runway (
    id                        BIGSERIAL PRIMARY KEY,
    airport_id                BIGINT      NOT NULL REFERENCES airport (id) ON DELETE CASCADE,
    designator                VARCHAR(5)  NOT NULL,
    threshold                 GEOGRAPHY(POINT, 4326) NOT NULL,
    magnetic_bearing_deg      DOUBLE PRECISION NOT NULL,
    length_ft                 INTEGER     NOT NULL,
    width_ft                  INTEGER     NOT NULL,
    threshold_elevation_ft    INTEGER     NOT NULL,
    displaced_threshold_ft    INTEGER     NOT NULL DEFAULT 0,
    gradient_percent          DOUBLE PRECISION NOT NULL DEFAULT 0,
    ils_ident                 VARCHAR(6),
    UNIQUE (airport_id, designator)
);

CREATE INDEX runway_airport_idx ON runway (airport_id);

CREATE TABLE navaid (
    id                       BIGSERIAL PRIMARY KEY,
    cycle_id                 BIGINT      NOT NULL REFERENCES airac_cycle (id) ON DELETE CASCADE,
    ident                    VARCHAR(5)  NOT NULL,
    icao_region              VARCHAR(2),
    name                     TEXT,
    -- VOR, VOR_DME, VORTAC, TACAN, DME, NDB, LOCATOR, ILS_LOCALISER.
    navaid_type              VARCHAR(16) NOT NULL,
    position                 GEOGRAPHY(POINT, 4326) NOT NULL,
    frequency_khz            INTEGER     NOT NULL,
    magnetic_variation_deg   DOUBLE PRECISION NOT NULL DEFAULT 0,
    elevation_ft             INTEGER
);

CREATE INDEX navaid_position_idx ON navaid USING GIST (position);
-- Identifiers repeat worldwide, so the lookup index is on the identifier alone and the caller
-- disambiguates by position. A unique constraint here would be wrong.
CREATE INDEX navaid_ident_idx ON navaid (cycle_id, ident);

CREATE TABLE waypoint (
    id                BIGSERIAL PRIMARY KEY,
    cycle_id          BIGINT      NOT NULL REFERENCES airac_cycle (id) ON DELETE CASCADE,
    ident             VARCHAR(5)  NOT NULL,
    icao_region       VARCHAR(2),
    name              TEXT,
    position          GEOGRAPHY(POINT, 4326) NOT NULL,
    -- ENROUTE, TERMINAL, BOTH.
    usage_type        VARCHAR(8)  NOT NULL,
    terminal_airport  VARCHAR(4)
);

CREATE INDEX waypoint_position_idx ON waypoint USING GIST (position);
CREATE INDEX waypoint_ident_idx ON waypoint (cycle_id, ident);

CREATE TABLE airway (
    id        BIGSERIAL PRIMARY KEY,
    cycle_id  BIGINT     NOT NULL REFERENCES airac_cycle (id) ON DELETE CASCADE,
    ident     VARCHAR(8) NOT NULL,
    UNIQUE (cycle_id, ident)
);

CREATE TABLE airway_point (
    id                    BIGSERIAL PRIMARY KEY,
    airway_id             BIGINT      NOT NULL REFERENCES airway (id) ON DELETE CASCADE,
    sequence_number       INTEGER     NOT NULL,
    fix_ident             VARCHAR(5)  NOT NULL,
    fix_region            VARCHAR(2),
    -- BOTH, FORWARD, BACKWARD.
    direction             VARCHAR(8)  NOT NULL DEFAULT 'BOTH',
    -- HIGH, LOW, ALL.
    level_band            VARCHAR(4)  NOT NULL DEFAULT 'ALL',
    minimum_altitude_ft   INTEGER,
    maximum_altitude_ft   INTEGER,
    description_code      VARCHAR(4),
    UNIQUE (airway_id, sequence_number)
);

CREATE INDEX airway_point_fix_idx ON airway_point (fix_ident);
