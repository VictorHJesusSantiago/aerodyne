package io.aerodyne.api.persistence;

import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.Navaid;
import io.aerodyne.navdata.NavaidType;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.WaypointUsage;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The persistent form of the navigation database.
 *
 * <p>These are storage records, not the domain model. The domain model — {@link Airport},
 * {@link Airway} and the rest — is immutable, has behaviour, and knows nothing about
 * persistence; these are mutable, have identity, and exist to be read and written. Each entity
 * carries a {@code toDomain} method and a static {@code from} method, and those two are the only
 * places the mapping lives.
 *
 * <p>Keeping the two apart costs a mapping. It buys a domain model that a test can construct in
 * one line without a database, which is what made every module underneath this one testable.
 *
 * <p>Every entity here is a nested class and each is given an explicit entity name. Without one
 * the name a JPQL query must use is the mangled binary name, and a query written against the
 * simple name fails at startup with "could not resolve root entity".
 */
public final class NavigationEntities {

    private NavigationEntities() {
    }

    /** One 28-day navigation data cycle. */
    @Entity(name = "CycleEntity")
    @Table(name = "airac_cycle")
    public static class CycleEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, unique = true, length = 4)
        private String identifier;

        @Column(name = "effective_date", nullable = false)
        private LocalDate effectiveDate;

        @Column(name = "expiry_date", nullable = false)
        private LocalDate expiryDate;

        @Column(name = "loaded_at", nullable = false)
        private Instant loadedAt = Instant.now();

        private String source;

        protected CycleEntity() {
        }

        public CycleEntity(AiracCycle cycle, String source) {
            this.identifier = cycle.identifier();
            this.effectiveDate = cycle.effectiveDate();
            this.expiryDate = cycle.expiryDate();
            this.source = source;
        }

        public Long getId() {
            return id;
        }

        public String getIdentifier() {
            return identifier;
        }

        public LocalDate getEffectiveDate() {
            return effectiveDate;
        }

        public LocalDate getExpiryDate() {
            return expiryDate;
        }

        public String getSource() {
            return source;
        }

        public Instant getLoadedAt() {
            return loadedAt;
        }

        public AiracCycle toDomain() {
            return AiracCycle.parse(identifier);
        }
    }

    /** An airport and the runways that hang off it. */
    @Entity(name = "AirportEntity")
    @Table(name = "airport")
    public static class AirportEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "cycle_id", nullable = false)
        private CycleEntity cycle;

        @Column(name = "icao_code", nullable = false, length = 4)
        private String icaoCode;

        @Column(name = "iata_code", length = 3)
        private String iataCode;

        private String name;

        /**
         * Declared as {@code geography}, not the {@code geometry} Hibernate assumes for a JTS
         * type. The distinction is not cosmetic: geography measures in metres on the spheroid,
         * which is what "within 200 nautical miles" means, while geometry would measure in
         * degrees and be wrong by the cosine of the latitude. The spatial queries depend on it,
         * so the column type is stated here rather than left to the default.
         */
        @Column(nullable = false, columnDefinition = "geography(Point,4326)")
        private Point position;

        @Column(name = "elevation_ft", nullable = false)
        private int elevationFt;

        @Column(name = "transition_altitude_ft")
        private Integer transitionAltitudeFt;

        @Column(name = "longest_runway_ft", nullable = false)
        private int longestRunwayFt;

        @Column(name = "public_military", length = 1)
        private String publicMilitary;

        @OneToMany(mappedBy = "airport", cascade = CascadeType.ALL, orphanRemoval = true,
                fetch = FetchType.EAGER)
        @OrderBy("designator")
        private List<RunwayEntity> runways = new ArrayList<>();

        protected AirportEntity() {
        }

        public static AirportEntity from(Airport airport, CycleEntity cycle) {
            AirportEntity entity = new AirportEntity();
            entity.cycle = cycle;
            entity.icaoCode = airport.icaoCode();
            entity.iataCode = airport.iataCode();
            entity.name = airport.name();
            entity.position = Positions.toPoint(airport.position());
            entity.elevationFt = airport.elevationFt();
            entity.transitionAltitudeFt = airport.transitionAltitudeFt();
            entity.longestRunwayFt = airport.longestRunwayFt();
            entity.publicMilitary = airport.publicMilitary();
            airport.runways().forEach(runway ->
                    entity.runways.add(RunwayEntity.from(runway, entity)));
            return entity;
        }

        public Airport toDomain() {
            return new Airport(icaoCode, iataCode, Positions.toGeoPoint(position), elevationFt,
                    transitionAltitudeFt, longestRunwayFt, publicMilitary, name,
                    runways.stream().map(RunwayEntity::toDomain).toList());
        }

        public Long getId() {
            return id;
        }

        public String getIcaoCode() {
            return icaoCode;
        }
    }

    /** One landing direction of a runway. */
    @Entity(name = "RunwayEntity")
    @Table(name = "runway")
    public static class RunwayEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "airport_id", nullable = false)
        private AirportEntity airport;

        @Column(nullable = false, length = 5)
        private String designator;

        @Column(nullable = false, columnDefinition = "geography(Point,4326)")
        private Point threshold;

        @Column(name = "magnetic_bearing_deg", nullable = false)
        private double magneticBearingDeg;

        @Column(name = "length_ft", nullable = false)
        private int lengthFt;

        @Column(name = "width_ft", nullable = false)
        private int widthFt;

        @Column(name = "threshold_elevation_ft", nullable = false)
        private int thresholdElevationFt;

        @Column(name = "displaced_threshold_ft", nullable = false)
        private int displacedThresholdFt;

        @Column(name = "gradient_percent", nullable = false)
        private double gradientPercent;

        @Column(name = "ils_ident", length = 6)
        private String ilsIdent;

        protected RunwayEntity() {
        }

        static RunwayEntity from(Runway runway, AirportEntity airport) {
            RunwayEntity entity = new RunwayEntity();
            entity.airport = airport;
            entity.designator = runway.designator();
            entity.threshold = Positions.toPoint(runway.threshold());
            entity.magneticBearingDeg = runway.magneticBearingDeg();
            entity.lengthFt = runway.lengthFt();
            entity.widthFt = runway.widthFt();
            entity.thresholdElevationFt = runway.thresholdElevationFt();
            entity.displacedThresholdFt = runway.displacedThresholdFt();
            entity.gradientPercent = runway.gradientPercent();
            entity.ilsIdent = runway.ilsIdent();
            return entity;
        }

        Runway toDomain() {
            return new Runway(airport.getIcaoCode(), designator, Positions.toGeoPoint(threshold),
                    magneticBearingDeg, lengthFt, widthFt, thresholdElevationFt,
                    displacedThresholdFt, gradientPercent, ilsIdent);
        }
    }

    /** A radio navigation aid. */
    @Entity(name = "NavaidEntity")
    @Table(name = "navaid")
    public static class NavaidEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "cycle_id", nullable = false)
        private CycleEntity cycle;

        @Column(nullable = false, length = 5)
        private String ident;

        @Column(name = "icao_region", length = 2)
        private String icaoRegion;

        private String name;

        @Enumerated(EnumType.STRING)
        @Column(name = "navaid_type", nullable = false, length = 16)
        private NavaidType navaidType;

        @Column(nullable = false, columnDefinition = "geography(Point,4326)")
        private Point position;

        @Column(name = "frequency_khz", nullable = false)
        private int frequencyKhz;

        @Column(name = "magnetic_variation_deg", nullable = false)
        private double magneticVariationDeg;

        @Column(name = "elevation_ft")
        private Integer elevationFt;

        protected NavaidEntity() {
        }

        public static NavaidEntity from(Navaid navaid, CycleEntity cycle) {
            NavaidEntity entity = new NavaidEntity();
            entity.cycle = cycle;
            entity.ident = navaid.ident();
            entity.icaoRegion = navaid.icaoRegion();
            entity.name = navaid.name();
            entity.navaidType = navaid.type();
            entity.position = Positions.toPoint(navaid.position());
            entity.frequencyKhz = navaid.frequencyKhz();
            entity.magneticVariationDeg = navaid.magneticVariationDeg();
            entity.elevationFt = navaid.elevationFt();
            return entity;
        }

        public Navaid toDomain() {
            return new Navaid(ident, icaoRegion, Positions.toGeoPoint(position), navaidType,
                    frequencyKhz, magneticVariationDeg, elevationFt, name);
        }
    }

    /** A named position with no radio aid behind it. */
    @Entity(name = "WaypointEntity")
    @Table(name = "waypoint")
    public static class WaypointEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "cycle_id", nullable = false)
        private CycleEntity cycle;

        @Column(nullable = false, length = 5)
        private String ident;

        @Column(name = "icao_region", length = 2)
        private String icaoRegion;

        private String name;

        @Column(nullable = false, columnDefinition = "geography(Point,4326)")
        private Point position;

        @Enumerated(EnumType.STRING)
        @Column(name = "usage_type", nullable = false, length = 8)
        private WaypointUsage usage;

        @Column(name = "terminal_airport", length = 4)
        private String terminalAirport;

        protected WaypointEntity() {
        }

        public static WaypointEntity from(Waypoint waypoint, CycleEntity cycle) {
            WaypointEntity entity = new WaypointEntity();
            entity.cycle = cycle;
            entity.ident = waypoint.ident();
            entity.icaoRegion = waypoint.icaoRegion();
            entity.name = waypoint.name();
            entity.position = Positions.toPoint(waypoint.position());
            entity.usage = waypoint.usage();
            entity.terminalAirport = waypoint.terminalAirport();
            return entity;
        }

        public Waypoint toDomain() {
            return new Waypoint(ident, icaoRegion, Positions.toGeoPoint(position), usage,
                    terminalAirport, name);
        }
    }

    /** An enroute airway and the points along it. */
    @Entity(name = "AirwayEntity")
    @Table(name = "airway")
    public static class AirwayEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "cycle_id", nullable = false)
        private CycleEntity cycle;

        @Column(nullable = false, length = 8)
        private String ident;

        @OneToMany(mappedBy = "airway", cascade = CascadeType.ALL, orphanRemoval = true,
                fetch = FetchType.EAGER)
        @OrderBy("sequenceNumber")
        private List<AirwayPointEntity> points = new ArrayList<>();

        protected AirwayEntity() {
        }

        public static AirwayEntity from(Airway airway, CycleEntity cycle) {
            AirwayEntity entity = new AirwayEntity();
            entity.cycle = cycle;
            entity.ident = airway.ident();
            airway.points().forEach(point ->
                    entity.points.add(AirwayPointEntity.from(point, entity)));
            return entity;
        }

        public Airway toDomain() {
            return new Airway(ident, points.stream().map(AirwayPointEntity::toDomain).toList());
        }
    }

    /** One point on an airway, with the rules for the leg leaving it. */
    @Entity(name = "AirwayPointEntity")
    @Table(name = "airway_point")
    public static class AirwayPointEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "airway_id", nullable = false)
        private AirwayEntity airway;

        @Column(name = "sequence_number", nullable = false)
        private int sequenceNumber;

        @Column(name = "fix_ident", nullable = false, length = 5)
        private String fixIdent;

        @Column(name = "fix_region", length = 2)
        private String fixRegion;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 8)
        private DirectionRestriction direction;

        @Enumerated(EnumType.STRING)
        @Column(name = "level_band", nullable = false, length = 4)
        private AirwayLevel levelBand;

        @Column(name = "minimum_altitude_ft")
        private Integer minimumAltitudeFt;

        @Column(name = "maximum_altitude_ft")
        private Integer maximumAltitudeFt;

        @Column(name = "description_code", length = 4)
        private String descriptionCode;

        protected AirwayPointEntity() {
        }

        static AirwayPointEntity from(AirwayPoint point, AirwayEntity airway) {
            AirwayPointEntity entity = new AirwayPointEntity();
            entity.airway = airway;
            entity.sequenceNumber = point.sequenceNumber();
            entity.fixIdent = point.fixIdent();
            entity.fixRegion = point.fixRegion();
            entity.direction = point.direction();
            entity.levelBand = point.level();
            entity.minimumAltitudeFt = point.minimumAltitudeFt();
            entity.maximumAltitudeFt = point.maximumAltitudeFt();
            // The column is fixed-width, and a blank description code is the common case.
            entity.descriptionCode = point.descriptionCode().isEmpty()
                    ? null
                    : point.descriptionCode();
            return entity;
        }

        AirwayPoint toDomain() {
            return new AirwayPoint(sequenceNumber, fixIdent, fixRegion, direction, levelBand,
                    minimumAltitudeFt, maximumAltitudeFt,
                    descriptionCode == null ? "" : descriptionCode);
        }
    }
}
