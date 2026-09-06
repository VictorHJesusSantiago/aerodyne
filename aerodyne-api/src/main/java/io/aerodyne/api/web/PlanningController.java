package io.aerodyne.api.web;

import io.aerodyne.api.dto.PlanRequest;
import io.aerodyne.api.dto.PlanResponse;
import io.aerodyne.api.persistence.FlightPlanEntity;
import io.aerodyne.api.persistence.CycleRepository;
import io.aerodyne.api.persistence.FlightPlanRepository;
import io.aerodyne.api.persistence.NavigationEntities;
import io.aerodyne.api.service.FlightPlanningService;
import io.aerodyne.dispatch.ofp.OfpRenderer;
import io.aerodyne.dispatch.ofp.OperationalFlightPlan;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Planning a flight and retrieving what was planned.
 *
 * <p>A plan that cannot be dispatched is still a plan: it is computed, stored and returned with
 * {@code 200}, carrying the objections that stop it. Returning an error status would be wrong
 * twice over — the request was valid and the computation succeeded — and it would push the
 * caller into treating a legitimate operational answer as a failure.
 *
 * <p>Statuses are used for what they are for: {@code 400} when the request cannot be planned at
 * all (an aerodrome not in the cycle, a type with no tables), {@code 404} for a plan that does
 * not exist, {@code 503} when no navigation data has been loaded.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanningController {

    private final FlightPlanningService planning;
    private final FlightPlanRepository plans;
    private final CycleRepository cycles;
    private final OfpRenderer renderer = new OfpRenderer();

    public PlanningController(FlightPlanningService planning, FlightPlanRepository plans,
                              CycleRepository cycles) {
        this.planning = planning;
        this.plans = plans;
        this.cycles = cycles;
    }

    /** Computes a plan and stores it. */
    @PostMapping
    public ResponseEntity<PlanResponse> plan(@Valid @RequestBody PlanRequest request) {
        OperationalFlightPlan plan = planning.plan(request);
        String ofp = renderer.render(plan);

        FlightPlanEntity stored = plans.save(FlightPlanEntity.from(plan,
                storedCycleFor(plan), ofp));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PlanResponse.from(stored.getReference(), plan));
    }

    /** A stored plan's summary. */
    @GetMapping("/{reference}")
    public ResponseEntity<StoredPlan> get(@PathVariable UUID reference) {
        return plans.findByReference(reference)
                .map(PlanningController::toStored)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PlanNotFoundException(reference));
    }

    /**
     * The rendered OFP, as plain text. This is the document that was handed over, served
     * verbatim from storage rather than re-rendered: re-rendering it would let an improvement to
     * the renderer change a document that has already been flown.
     */
    @GetMapping(value = "/{reference}/ofp", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ofp(@PathVariable UUID reference) {
        return plans.findByReference(reference)
                .map(FlightPlanEntity::getOfpText)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PlanNotFoundException(reference));
    }

    /** A stored plan as the API reports it. */
    public record StoredPlan(UUID reference, String flightNumber, String registration,
                             String aircraftType, String departure, String destination,
                             String route, int cruiseFlightLevel,
                             java.time.Instant offBlocks, double distanceNm,
                             double tripTimeMinutes, double tripFuelKg, double rampFuelKg,
                             double takeoffWeightKg, double landingWeightKg,
                             boolean dispatchable, java.util.List<String> objections,
                             String weatherSource, java.time.Instant computedAt) {
    }

    private static StoredPlan toStored(FlightPlanEntity entity) {
        return new StoredPlan(entity.getReference(), entity.getFlightNumber(),
                entity.getRegistration(), entity.getAircraftType(), entity.getDepartureIcao(),
                entity.getDestinationIcao(), entity.getRouteString(), entity.getCruiseAltitudeFt() / 100,
                entity.getOffBlocks(), entity.getDistanceNm(), entity.getTripTimeMinutes(),
                entity.getTripFuelKg(), entity.getRampFuelKg(), entity.getTakeoffWeightKg(),
                entity.getLandingWeightKg(), entity.isDispatchable(), entity.getObjections(),
                entity.getWeatherSource(), entity.getComputedAt());
    }

    /**
     * The stored cycle row the plan was built against.
     *
     * <p>The planning service works from the in-memory database, which carries the cycle's
     * identifier but not the row it came from, so the row is looked up by that identifier. A
     * plan whose cycle is not in the tables cannot be stored: the whole point of keeping the
     * cycle alongside the plan is to be able to recompute it, and a dangling reference would
     * make that impossible while looking as though it were fine.
     */
    private NavigationEntities.CycleEntity storedCycleFor(OperationalFlightPlan plan) {
        if (plan.airacCycle() == null) {
            throw new IllegalStateException(
                    "the plan carries no AIRAC cycle and cannot be filed against one");
        }
        String identifier = plan.airacCycle().identifier();
        return cycles.findByIdentifier(identifier).orElseThrow(() -> new IllegalStateException(
                "AIRAC cycle " + identifier + " is loaded in memory but not in the database"));
    }

    /** Raised when a plan reference does not exist. */
    public static class PlanNotFoundException extends RuntimeException {

        public PlanNotFoundException(UUID reference) {
            super("no flight plan with reference " + reference);
        }
    }
}
