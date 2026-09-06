-- Flight plans, and the record of what each one was computed from.
--
-- A plan is stored as its inputs, its outputs and the identity of the data it used. That is
-- what makes it auditable: the question after an event is never "what did the plan say" — the
-- printed OFP says that — but "was the plan right given what was known", and answering it
-- means being able to recompute it against the same cycle and the same forecast.

-- Character columns are VARCHAR, never CHAR. PostgreSQL blank-pads a CHAR, so a two-letter
-- region stored in CHAR(2) is fine but a shorter value comes back padded, and code that compares
-- it against the value it wrote gets a mismatch it cannot see. VARCHAR stores exactly what it is
-- given, which is also what the ORM expects a String to map to.--
-- Numeric columns are DOUBLE PRECISION, not NUMERIC. The domain works in doubles throughout,
-- because these are physical quantities and not money: a fuel figure is the result of an
-- integration and carries no exact decimal value to preserve. NUMERIC would force a conversion
-- at every read and write, and the ORM would reject the mismatch anyway.
CREATE TABLE flight_plan (
    id                     BIGSERIAL PRIMARY KEY,
    reference              UUID        NOT NULL UNIQUE,
    cycle_id               BIGINT      NOT NULL REFERENCES airac_cycle (id),
    flight_number          VARCHAR(8),
    registration           VARCHAR(8),
    aircraft_type          VARCHAR(4)  NOT NULL,
    departure_icao         VARCHAR(4)  NOT NULL,
    destination_icao       VARCHAR(4)  NOT NULL,
    route_string           TEXT        NOT NULL,
    cruise_altitude_ft     INTEGER     NOT NULL,
    off_blocks             TIMESTAMPTZ NOT NULL,
    -- The great-circle path actually flown, for map display and for after-the-fact comparison
    -- against the track flown. A linestring rather than points: the order is the data.
    path                   GEOGRAPHY(LINESTRING, 4326),
    distance_nm            DOUBLE PRECISION  NOT NULL,
    trip_time_minutes      DOUBLE PRECISION  NOT NULL,
    trip_fuel_kg           DOUBLE PRECISION NOT NULL,
    ramp_fuel_kg           DOUBLE PRECISION NOT NULL,
    takeoff_weight_kg      DOUBLE PRECISION NOT NULL,
    landing_weight_kg      DOUBLE PRECISION NOT NULL,
    weather_source         TEXT,
    dispatchable           BOOLEAN     NOT NULL,
    computed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    ofp_text               TEXT,
    CONSTRAINT flight_plan_positive_distance CHECK (distance_nm > 0)
);

CREATE INDEX flight_plan_departure_idx ON flight_plan (departure_icao, off_blocks);
CREATE INDEX flight_plan_path_idx ON flight_plan USING GIST (path);

COMMENT ON COLUMN flight_plan.dispatchable IS
    'Whether the plan had no objections when it was computed. A plan is stored either way: a '
    'refused plan is a record of a decision too.';

CREATE TABLE flight_plan_objection (
    id              BIGSERIAL PRIMARY KEY,
    flight_plan_id  BIGINT NOT NULL REFERENCES flight_plan (id) ON DELETE CASCADE,
    ordinal         INTEGER NOT NULL,
    objection       TEXT   NOT NULL,
    UNIQUE (flight_plan_id, ordinal)
);

CREATE TABLE flight_plan_alternate (
    id              BIGSERIAL PRIMARY KEY,
    flight_plan_id  BIGINT NOT NULL REFERENCES flight_plan (id) ON DELETE CASCADE,
    ordinal         INTEGER NOT NULL,
    icao_code       VARCHAR(4) NOT NULL,
    distance_nm     DOUBLE PRECISION NOT NULL,
    usable          BOOLEAN NOT NULL,
    UNIQUE (flight_plan_id, ordinal)
);

-- Datalink messages, kept so that an exchange can be reconstructed afterwards. The reference
-- column is what pairs a reply with the clearance it answers.
CREATE TABLE cpdlc_message (
    id                    BIGSERIAL PRIMARY KEY,
    flight_plan_id        BIGINT REFERENCES flight_plan (id) ON DELETE CASCADE,
    message_id            INTEGER     NOT NULL,
    reference_id          INTEGER,
    sender                VARCHAR(16) NOT NULL,
    recipient             VARCHAR(16) NOT NULL,
    sent_at               TIMESTAMPTZ NOT NULL,
    response_requirement  VARCHAR(2)  NOT NULL,
    wire_format           TEXT        NOT NULL,
    UNIQUE (flight_plan_id, sender, message_id)
);

CREATE INDEX cpdlc_message_reference_idx ON cpdlc_message (flight_plan_id, reference_id);
