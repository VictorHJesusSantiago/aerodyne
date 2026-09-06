package io.aerodyne.api.persistence;

import io.aerodyne.navdata.procedure.AltitudeConstraint;
import io.aerodyne.navdata.procedure.LegType;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureLeg;
import io.aerodyne.navdata.procedure.ProcedureTransition;
import io.aerodyne.navdata.procedure.ProcedureType;
import io.aerodyne.navdata.procedure.SpeedConstraint;
import io.aerodyne.navdata.procedure.TransitionKind;
import io.aerodyne.navdata.procedure.TurnDirection;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The persistent form of a terminal procedure.
 *
 * <p>Three tables deep, mirroring the domain: a procedure owns its segments and a segment owns
 * its legs. The nesting is not gratuitous — it is what lets a departure store its shared middle
 * once and its runway-specific starts separately, which is how the published data is organised
 * and how it has to be corrected.
 *
 * <p>The segments carry an ordinal as well as their identifier, so that the order they were
 * published in survives the round trip. It has no operational meaning, but a procedure that came
 * back with its transitions shuffled would be a different object from the one that went in, and
 * the tests that compare the two would have to be weakened to allow it.
 */
@Entity
@Table(name = "procedure_definition")
public class ProcedureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private NavigationEntities.CycleEntity cycle;

    @Column(name = "airport_icao", nullable = false, length = 4)
    private String airportIcao;

    @Column(nullable = false, length = 8)
    private String ident;

    @Enumerated(EnumType.STRING)
    @Column(name = "procedure_type", nullable = false, length = 8)
    private ProcedureType procedureType;

    @Column(name = "route_type", nullable = false, length = 1)
    private String routeType;

    @OneToMany(mappedBy = "procedure", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("ordinal")
    private List<TransitionEntity> transitions = new ArrayList<>();

    protected ProcedureEntity() {
    }

    public static ProcedureEntity from(Procedure procedure, NavigationEntities.CycleEntity cycle) {
        ProcedureEntity entity = new ProcedureEntity();
        entity.cycle = cycle;
        entity.airportIcao = procedure.airportIcao();
        entity.ident = procedure.ident();
        entity.procedureType = procedure.type();
        entity.routeType = String.valueOf(procedure.routeType());
        List<ProcedureTransition> segments = procedure.transitions();
        for (int i = 0; i < segments.size(); i++) {
            entity.transitions.add(TransitionEntity.from(segments.get(i), i, entity));
        }
        return entity;
    }

    public Procedure toDomain() {
        return new Procedure(airportIcao, ident, procedureType,
                routeType.isEmpty() ? ' ' : routeType.charAt(0),
                transitions.stream().map(TransitionEntity::toDomain).toList());
    }

    public Long getId() {
        return id;
    }

    public String getAirportIcao() {
        return airportIcao;
    }

    public String getIdent() {
        return ident;
    }

    /** One segment of a procedure. */
    @Entity(name = "TransitionEntity")
    @Table(name = "procedure_transition")
    public static class TransitionEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "procedure_id", nullable = false)
        private ProcedureEntity procedure;

        @Column(nullable = false)
        private int ordinal;

        @Enumerated(EnumType.STRING)
        @Column(name = "transition_kind", nullable = false, length = 20)
        private TransitionKind transitionKind;

        @Column(nullable = false, length = 8)
        private String identifier;

        @Column(name = "route_type", nullable = false, length = 1)
        private String routeType;

        @OneToMany(mappedBy = "transition", cascade = CascadeType.ALL, orphanRemoval = true,
                fetch = FetchType.EAGER)
        @OrderBy("sequenceNumber")
        private List<LegEntity> legs = new ArrayList<>();

        protected TransitionEntity() {
        }

        static TransitionEntity from(ProcedureTransition transition, int ordinal,
                                     ProcedureEntity procedure) {
            TransitionEntity entity = new TransitionEntity();
            entity.procedure = procedure;
            entity.ordinal = ordinal;
            entity.transitionKind = transition.kind();
            entity.identifier = transition.identifier();
            entity.routeType = String.valueOf(transition.routeType());
            transition.legs().forEach(leg -> entity.legs.add(LegEntity.from(leg, entity)));
            return entity;
        }

        ProcedureTransition toDomain() {
            return new ProcedureTransition(transitionKind, identifier,
                    routeType.isEmpty() ? ' ' : routeType.charAt(0),
                    legs.stream().map(LegEntity::toDomain).toList());
        }
    }

    /** One leg of one segment. */
    @Entity(name = "LegEntity")
    @Table(name = "procedure_leg")
    public static class LegEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "transition_id", nullable = false)
        private TransitionEntity transition;

        @Column(name = "sequence_number", nullable = false)
        private int sequenceNumber;

        @Enumerated(EnumType.STRING)
        @Column(name = "leg_type", nullable = false, length = 8)
        private LegType legType;

        @Column(name = "fix_ident", length = 5)
        private String fixIdent;

        @Column(name = "fix_region", length = 2)
        private String fixRegion;

        @Enumerated(EnumType.STRING)
        @Column(name = "turn_direction", nullable = false, length = 12)
        private TurnDirection turnDirection;

        @Column(name = "recommended_navaid", length = 5)
        private String recommendedNavaid;

        @Column(name = "navaid_region", length = 2)
        private String navaidRegion;

        @Column(name = "theta_deg")
        private Double thetaDeg;

        @Column(name = "rho_nm")
        private Double rhoNm;

        @Column(name = "magnetic_course_deg")
        private Double magneticCourseDeg;

        @Column(name = "route_distance_nm")
        private Double routeDistanceNm;

        @Enumerated(EnumType.STRING)
        @Column(name = "altitude_kind", nullable = false, length = 20)
        private AltitudeConstraint.Kind altitudeKind;

        @Column(name = "altitude_one_ft")
        private Integer altitudeOneFt;

        @Column(name = "altitude_two_ft")
        private Integer altitudeTwoFt;

        @Enumerated(EnumType.STRING)
        @Column(name = "speed_kind", nullable = false, length = 12)
        private SpeedConstraint.Kind speedKind;

        @Column(name = "speed_kt", nullable = false)
        private int speedKt;

        @Column(name = "description_code", length = 4)
        private String descriptionCode;

        @Column(name = "vertical_angle_deg")
        private Double verticalAngleDeg;

        protected LegEntity() {
        }

        static LegEntity from(ProcedureLeg leg, TransitionEntity transition) {
            LegEntity entity = new LegEntity();
            entity.transition = transition;
            entity.sequenceNumber = leg.sequenceNumber();
            entity.legType = leg.legType();
            entity.fixIdent = leg.fixIdent();
            entity.fixRegion = leg.fixRegion();
            entity.turnDirection = leg.turnDirection();
            entity.recommendedNavaid = leg.recommendedNavaid();
            entity.navaidRegion = leg.navaidRegion();
            entity.thetaDeg = leg.thetaDeg();
            entity.rhoNm = leg.rhoNm();
            entity.magneticCourseDeg = leg.magneticCourseDeg();
            entity.routeDistanceNm = leg.routeDistanceNm();
            entity.altitudeKind = leg.altitude().kind();
            entity.altitudeOneFt = leg.altitude().altitudeOne();
            entity.altitudeTwoFt = leg.altitude().altitudeTwo();
            entity.speedKind = leg.speed().kind();
            entity.speedKt = leg.speed().speedKt();
            // The column is fixed-width and the code is positional, so a blank one is stored as
            // null rather than as four spaces that would come back looking like data.
            entity.descriptionCode = leg.descriptionCode().isBlank() ? null : leg.descriptionCode();
            entity.verticalAngleDeg = leg.verticalAngleDeg();
            return entity;
        }

        ProcedureLeg toDomain() {
            return new ProcedureLeg(sequenceNumber, legType, fixIdent, fixRegion, turnDirection,
                    recommendedNavaid, navaidRegion, thetaDeg, rhoNm, magneticCourseDeg,
                    routeDistanceNm,
                    new AltitudeConstraint(altitudeKind, altitudeOneFt, altitudeTwoFt),
                    new SpeedConstraint(speedKind, speedKt),
                    descriptionCode == null ? "" : descriptionCode,
                    verticalAngleDeg);
        }
    }
}
