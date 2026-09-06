package io.aerodyne.api.persistence;

import io.aerodyne.dispatch.ofp.OperationalFlightPlan;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.LineString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A computed flight plan, stored.
 *
 * <p>What is kept is the answer and enough of the question to reproduce it: the route, the
 * cruise level, the off-blocks time, the cycle it was planned against and where the winds came
 * from. The rendered OFP is kept alongside, because that is the document that was actually
 * handed over and it must not change if the engine is later improved.
 *
 * <p>A plan that could not be dispatched is stored too. Refusing to dispatch is a decision, and
 * a system that only recorded the plans that succeeded would have no record of the ones that
 * mattered most.
 */
@Entity
@Table(name = "flight_plan")
public class FlightPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The public identifier; the primary key never leaves the database. */
    @Column(nullable = false, unique = true)
    private UUID reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private NavigationEntities.CycleEntity cycle;

    @Column(name = "flight_number", length = 8)
    private String flightNumber;

    @Column(length = 8)
    private String registration;

    @Column(name = "aircraft_type", nullable = false, length = 4)
    private String aircraftType;

    @Column(name = "departure_icao", nullable = false, length = 4)
    private String departureIcao;

    @Column(name = "destination_icao", nullable = false, length = 4)
    private String destinationIcao;

    @Column(name = "route_string", nullable = false)
    private String routeString;

    @Column(name = "cruise_altitude_ft", nullable = false)
    private int cruiseAltitudeFt;

    @Column(name = "off_blocks", nullable = false)
    private Instant offBlocks;

    /** Geography, for the same reason the fix positions are: see {@link NavigationEntities}. */
    @Column(columnDefinition = "geography(LineString,4326)")
    private LineString path;

    @Column(name = "distance_nm", nullable = false)
    private double distanceNm;

    @Column(name = "trip_time_minutes", nullable = false)
    private double tripTimeMinutes;

    @Column(name = "trip_fuel_kg", nullable = false)
    private double tripFuelKg;

    @Column(name = "ramp_fuel_kg", nullable = false)
    private double rampFuelKg;

    @Column(name = "takeoff_weight_kg", nullable = false)
    private double takeoffWeightKg;

    @Column(name = "landing_weight_kg", nullable = false)
    private double landingWeightKg;

    @Column(name = "weather_source")
    private String weatherSource;

    @Column(nullable = false)
    private boolean dispatchable;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    @Column(name = "ofp_text")
    private String ofpText;

    @OneToMany(mappedBy = "flightPlan", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("ordinal")
    private List<ObjectionEntity> objections = new ArrayList<>();

    @OneToMany(mappedBy = "flightPlan", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("ordinal")
    private List<AlternateEntity> alternates = new ArrayList<>();

    protected FlightPlanEntity() {
    }

    /** Builds the storage record from a computed plan. */
    public static FlightPlanEntity from(OperationalFlightPlan plan,
                                        NavigationEntities.CycleEntity cycle,
                                        String ofpText) {
        FlightPlanEntity entity = new FlightPlanEntity();
        entity.reference = UUID.randomUUID();
        entity.cycle = cycle;
        entity.flightNumber = plan.flightNumber();
        entity.registration = plan.registration();
        entity.aircraftType = plan.aircraftType().typeCode();
        entity.departureIcao = plan.departure().icaoCode();
        entity.destinationIcao = plan.destination().icaoCode();
        entity.routeString = plan.routeString();
        entity.cruiseAltitudeFt = (int) Math.round(plan.profile().cruiseAltitudeFt());
        entity.offBlocks = plan.offBlocks();
        // Each leg contributes its start; the last leg's end closes the line, which a stream of
        // starts alone would leave off.
        List<io.aerodyne.geo.GeoPoint> path = new ArrayList<>();
        plan.profile().legs().forEach(leg -> path.add(leg.from()));
        path.add(plan.profile().legs().get(plan.profile().legs().size() - 1).to());
        entity.path = Positions.toLineString(path);
        entity.distanceNm = plan.distanceNm();
        entity.tripTimeMinutes = plan.profile().tripTimeMinutes();
        entity.tripFuelKg = plan.fuel().tripKg();
        entity.rampFuelKg = plan.fuel().rampFuelKg();
        entity.takeoffWeightKg = plan.loadsheet().takeoffWeightKg();
        entity.landingWeightKg = plan.loadsheet().landingWeightKg();
        entity.weatherSource = plan.weatherSource();
        entity.dispatchable = plan.isDispatchable();
        entity.computedAt = plan.preparedAt();
        entity.ofpText = ofpText;

        List<String> objections = plan.dispatchObjections();
        for (int i = 0; i < objections.size(); i++) {
            entity.objections.add(new ObjectionEntity(entity, i, objections.get(i)));
        }
        for (int i = 0; i < plan.alternates().size(); i++) {
            var alternate = plan.alternates().get(i);
            entity.alternates.add(new AlternateEntity(entity, i, alternate.icaoCode(),
                    alternate.distanceNm(), alternate.isUsable()));
        }
        return entity;
    }

    public Long getId() {
        return id;
    }

    public UUID getReference() {
        return reference;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public String getRegistration() {
        return registration;
    }

    public String getAircraftType() {
        return aircraftType;
    }

    public String getDepartureIcao() {
        return departureIcao;
    }

    public String getDestinationIcao() {
        return destinationIcao;
    }

    public String getRouteString() {
        return routeString;
    }

    public int getCruiseAltitudeFt() {
        return cruiseAltitudeFt;
    }

    public Instant getOffBlocks() {
        return offBlocks;
    }

    public double getDistanceNm() {
        return distanceNm;
    }

    public double getTripTimeMinutes() {
        return tripTimeMinutes;
    }

    public double getTripFuelKg() {
        return tripFuelKg;
    }

    public double getRampFuelKg() {
        return rampFuelKg;
    }

    public double getTakeoffWeightKg() {
        return takeoffWeightKg;
    }

    public double getLandingWeightKg() {
        return landingWeightKg;
    }

    public String getWeatherSource() {
        return weatherSource;
    }

    public boolean isDispatchable() {
        return dispatchable;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public String getOfpText() {
        return ofpText;
    }

    public List<String> getObjections() {
        return objections.stream().map(ObjectionEntity::getObjection).toList();
    }

    public List<AlternateEntity> getAlternates() {
        return List.copyOf(alternates);
    }

    public NavigationEntities.CycleEntity getCycle() {
        return cycle;
    }

    /** One reason a plan could not be dispatched. */
    @Entity(name = "ObjectionEntity")
    @Table(name = "flight_plan_objection")
    public static class ObjectionEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "flight_plan_id", nullable = false)
        private FlightPlanEntity flightPlan;

        @Column(nullable = false)
        private int ordinal;

        @Column(nullable = false)
        private String objection;

        protected ObjectionEntity() {
        }

        ObjectionEntity(FlightPlanEntity flightPlan, int ordinal, String objection) {
            this.flightPlan = flightPlan;
            this.ordinal = ordinal;
            this.objection = objection;
        }

        public String getObjection() {
            return objection;
        }
    }

    /** One alternate considered for the plan. */
    @Entity(name = "AlternateEntity")
    @Table(name = "flight_plan_alternate")
    public static class AlternateEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "flight_plan_id", nullable = false)
        private FlightPlanEntity flightPlan;

        @Column(nullable = false)
        private int ordinal;

        @Column(name = "icao_code", nullable = false, length = 4)
        private String icaoCode;

        @Column(name = "distance_nm", nullable = false)
        private double distanceNm;

        @Column(nullable = false)
        private boolean usable;

        protected AlternateEntity() {
        }

        AlternateEntity(FlightPlanEntity flightPlan, int ordinal, String icaoCode,
                        double distanceNm, boolean usable) {
            this.flightPlan = flightPlan;
            this.ordinal = ordinal;
            this.icaoCode = icaoCode;
            this.distanceNm = distanceNm;
            this.usable = usable;
        }

        public String getIcaoCode() {
            return icaoCode;
        }

        public double getDistanceNm() {
            return distanceNm;
        }

        public boolean isUsable() {
            return usable;
        }
    }
}
