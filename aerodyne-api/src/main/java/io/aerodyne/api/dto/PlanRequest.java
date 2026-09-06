package io.aerodyne.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

/**
 * A request to plan a flight.
 *
 * <p>Only the departure, the destination, the aircraft type and the off-blocks time are
 * required. Everything else has a defensible default: with no route, one is searched for over
 * the airway network; with no cruise level, one is chosen from the aircraft's optimum altitude
 * and the semicircular rule; with no alternates, they are selected from what is near the
 * destination and forecast to be usable.
 *
 * @param flightNumber      the commercial flight number, for the document
 * @param registration      the aircraft's registration
 * @param aircraftType      the ICAO type designator; the installation must hold its tables
 * @param departure         the departure aerodrome
 * @param destination       the destination aerodrome
 * @param route             the route to fly, or {@code null} to search for one
 * @param cruiseFlightLevel the level to cruise at, or {@code null} to choose one
 * @param offBlocks         when the flight starts
 * @param payloadKg         passengers and cargo
 * @param extraFuelKg       fuel at the commander's discretion, beyond the required minimum
 * @param departureRunway   the runway in use at the departure, which decides which segment of a
 *                          departure procedure applies
 * @param sid               the departure procedure to fly, or {@code null} to pick one that
 *                          joins the route
 * @param arrivalRunway     the runway expected at the destination
 * @param star              the arrival procedure, or {@code null} to pick one
 * @param alternates        alternates to use instead of searching for them
 * @param windOptimalRoute  search for the quickest route against the forecast rather than the
 *                          shortest one; {@code null} means the shortest, which is what a
 *                          request with no forecast loaded would get anyway
 * @param stepClimb         plan step climbs as the aircraft gets lighter; {@code null} means a
 *                          single cruise level throughout
 */
public record PlanRequest(
        @Pattern(regexp = "[A-Z0-9]{2,8}", message = "a flight number is two to eight characters")
        String flightNumber,

        @Pattern(regexp = "[A-Z0-9-]{2,8}", message = "a registration is two to eight characters")
        String registration,

        @NotBlank(message = "an aircraft type is required")
        @Pattern(regexp = "[A-Z0-9]{2,4}", message = "an ICAO type designator is two to four characters")
        String aircraftType,

        @NotBlank(message = "a departure aerodrome is required")
        @Pattern(regexp = "[A-Z]{4}", message = "an ICAO location indicator is four letters")
        String departure,

        @NotBlank(message = "a destination aerodrome is required")
        @Pattern(regexp = "[A-Z]{4}", message = "an ICAO location indicator is four letters")
        String destination,

        String route,

        Integer cruiseFlightLevel,

        @NotNull(message = "an off-blocks time is required")
        Instant offBlocks,

        @PositiveOrZero(message = "payload cannot be negative")
        double payloadKg,

        @PositiveOrZero(message = "extra fuel cannot be negative")
        double extraFuelKg,

        @Pattern(regexp = "(RW)?[0-3][0-9][LRCB]?", message = "a runway is a two-digit "
                + "designator with an optional side, as 27L")
        String departureRunway,

        @Pattern(regexp = "[A-Z0-9]{1,8}", message = "a procedure identifier is up to eight "
                + "characters")
        String sid,

        @Pattern(regexp = "(RW)?[0-3][0-9][LRCB]?", message = "a runway is a two-digit "
                + "designator with an optional side, as 27L")
        String arrivalRunway,

        @Pattern(regexp = "[A-Z0-9]{1,8}", message = "a procedure identifier is up to eight "
                + "characters")
        String star,

        List<String> alternates,

        Boolean windOptimalRoute,

        Boolean stepClimb) {

    /** A request in the shape the API had before wind routing and step climbs were options. */
    public PlanRequest(String flightNumber, String registration, String aircraftType,
                       String departure, String destination, String route,
                       Integer cruiseFlightLevel, Instant offBlocks, double payloadKg,
                       double extraFuelKg, String departureRunway, String sid,
                       String arrivalRunway, String star, List<String> alternates) {
        this(flightNumber, registration, aircraftType, departure, destination, route,
                cruiseFlightLevel, offBlocks, payloadKg, extraFuelKg, departureRunway, sid,
                arrivalRunway, star, alternates, null, null);
    }

    public PlanRequest {
        alternates = alternates == null ? List.of() : List.copyOf(alternates);
    }

    /** True when the caller left the route to the engine. */
    public boolean needsRouteSearch() {
        return route == null || route.isBlank();
    }

    /** True when the caller left the cruise level to the engine. */
    public boolean needsLevelSelection() {
        return cruiseFlightLevel == null;
    }

    /**
     * True when the caller wants terminal procedures attached at all.
     *
     * <p>Naming a runway is enough: a runway is what selects a departure's first segment, and a
     * caller who knows the runway is asking for the procedure that goes with it. Naming neither
     * a runway nor a procedure means plan from the aerodrome, which is what a first look at a
     * sector wants.
     */
    public boolean wantsProcedures() {
        return departureRunway != null || sid != null || arrivalRunway != null || star != null;
    }

    /**
     * True when the route search should minimise time against the forecast rather than distance.
     *
     * <p>Off by default, and deliberately. A wind-optimal route is longer than the shortest one
     * and looks wrong next to a chart, so it is something a dispatcher asks for knowing what it
     * will produce, rather than something that changes under them.
     */
    public boolean optimiseForWind() {
        return Boolean.TRUE.equals(windOptimalRoute);
    }

    /** True when the cruise may step up to higher levels as the fuel burns off. */
    public boolean wantsStepClimb() {
        return Boolean.TRUE.equals(stepClimb);
    }
}
