package io.aerodyne.dispatch.ofp;

import io.aerodyne.dispatch.AlternateCandidate;
import io.aerodyne.dispatch.EqualTimePoint;
import io.aerodyne.dispatch.EtopsAnalysis;
import io.aerodyne.geo.CoordinateFormat;
import io.aerodyne.geo.Units;
import io.aerodyne.performance.FlightPhase;
import io.aerodyne.performance.ProfileLeg;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders an operational flight plan as the fixed-width text document a crew is handed.
 *
 * <p>The layout is column-aligned monospace rather than anything prettier, and deliberately so:
 * an OFP is read in a cockpit, cross-checked against an FMS a line at a time, and often printed
 * on a dot-matrix printer at the gate. Alignment is what makes a column of fuel figures
 * checkable at a glance, and a proportional font destroys it.
 *
 * <p>The order follows what gets read when: the header and the fuel while the crew is still on
 * the ground deciding, then the navigation log they will follow, then the ETOPS and alternate
 * pages they will need only if something goes wrong.
 */
public final class OfpRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("ddHHmm'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final String RULE = "=".repeat(78);
    private static final String THIN_RULE = "-".repeat(78);

    public String render(OperationalFlightPlan plan) {
        StringBuilder out = new StringBuilder(4_096);
        header(out, plan);
        fuel(out, plan);
        weights(out, plan);
        navigationLog(out, plan);
        etops(out, plan);
        alternates(out, plan);
        release(out, plan);
        return out.toString();
    }

    private void header(StringBuilder out, OperationalFlightPlan plan) {
        out.append(RULE).append('\n');
        out.append(String.format("OPERATIONAL FLIGHT PLAN%54s%n",
                "PREPARED " + DATE_TIME.format(plan.preparedAt())));
        out.append(RULE).append('\n');
        out.append(String.format("%-12s %-10s %-10s %-12s %s%n",
                "FLIGHT", "REG", "TYPE", "SECTOR", "OFF BLOCKS"));
        out.append(String.format("%-12s %-10s %-10s %-12s %s%n",
                orDash(plan.flightNumber()), orDash(plan.registration()),
                plan.aircraftType().typeCode(), plan.sectorLabel(), TIME.format(plan.offBlocks())));
        out.append('\n');
        out.append(String.format("%-12s %s (%s)%n", "FROM",
                plan.departure().icaoCode(), orDash(plan.departure().name())));
        out.append(String.format("%-12s %s (%s)%n", "TO",
                plan.destination().icaoCode(), orDash(plan.destination().name())));
        out.append(String.format("%-12s %.0f nm, %s, cruise FL%d%n", "PLANNED",
                plan.distanceNm(), formatDuration(plan.profile().tripTime()),
                Units.pressureAltitudeToFlightLevel(plan.profile().cruiseAltitudeFt())));
        out.append(String.format("%-12s %s%n", "ETA", TIME.format(plan.estimatedArrival())));
        out.append(String.format("%-12s AIRAC %s, winds from %s%n", "DATA",
                plan.airacCycle() == null ? "unknown" : plan.airacCycle().identifier(),
                plan.weatherSource()));
        out.append('\n');
        out.append("ROUTE\n");
        out.append(wrap(plan.routeString(), 76, "  ")).append('\n');
    }

    private void fuel(StringBuilder out, OperationalFlightPlan plan) {
        out.append('\n').append(RULE).append('\n');
        out.append("FUEL\n").append(THIN_RULE).append('\n');
        plan.fuel().asOfpRows().forEach((label, kilograms) ->
                out.append(String.format("  %-10s %9.0f kg%n", label, kilograms)));
        out.append(String.format("%n  Average wind component %+.0f kt, average ground speed %.0f kt%n",
                -plan.profile().averageHeadwindKt(), plan.profile().averageGroundSpeedKt()));
    }

    private void weights(StringBuilder out, OperationalFlightPlan plan) {
        out.append('\n').append(RULE).append('\n');
        out.append("WEIGHTS AND BALANCE\n").append(THIN_RULE).append('\n');
        out.append(String.format("  %-22s %9.0f kg    CG %5.1f%% MAC%n", "Zero fuel weight",
                plan.loadsheet().zeroFuelWeightKg(), plan.loadsheet().zeroFuelCgPercentMac()));
        out.append(String.format("  %-22s %9.0f kg    CG %5.1f%% MAC%n", "Takeoff weight",
                plan.loadsheet().takeoffWeightKg(), plan.loadsheet().takeoffCgPercentMac()));
        out.append(String.format("  %-22s %9.0f kg    CG %5.1f%% MAC%n", "Landing weight",
                plan.loadsheet().landingWeightKg(), plan.loadsheet().landingCgPercentMac()));
        plan.loadsheet().takeoffCgLimits().ifPresent(limits ->
                out.append(String.format("  %-22s %9s       %5.1f to %5.1f%% MAC%n",
                        "Takeoff CG limits", "", limits.forwardPercentMac(), limits.aftPercentMac())));
        out.append(String.format("  %-22s %9.0f kg%n", "Payload", plan.loadsheet().payloadKg()));
    }

    private void navigationLog(StringBuilder out, OperationalFlightPlan plan) {
        out.append('\n').append(RULE).append('\n');
        out.append("NAVIGATION LOG\n").append(THIN_RULE).append('\n');
        out.append(String.format("  %-19s %-4s %5s %4s %6s %5s %5s %6s %7s%n",
                "POSITION", "PH", "FL", "TRK", "WIND", "GS", "DIST", "ETE", "FUEL"));
        for (ProfileLeg leg : plan.profile().legs()) {
            out.append(String.format("  %-19s %-4s %5d %4.0f %6s %5.0f %5.0f %6s %7.0f%n",
                    CoordinateFormat.toArincDegreesMinutes(leg.to()),
                    abbreviate(leg.phase()),
                    Units.pressureAltitudeToFlightLevel(leg.altitudeFt()),
                    leg.trueCourseDeg(),
                    leg.wind().toOfpString(),
                    leg.groundSpeedKt(),
                    leg.distanceNm(),
                    formatMinutes(leg.elapsedMinutes()),
                    leg.cumulativeFuelKg()));
        }
        out.append(THIN_RULE).append('\n');
        out.append(String.format("  %-19s %-4s %5s %4s %6s %5s %5.0f %6s %7.0f%n",
                "TOTAL", "", "", "", "", "", plan.profile().distanceNm(),
                formatMinutes(plan.profile().tripTimeMinutes()), plan.profile().tripFuelKg()));
    }

    private void etops(StringBuilder out, OperationalFlightPlan plan) {
        EtopsAnalysis analysis = plan.etops();
        if (analysis == null) {
            return;
        }
        out.append('\n').append(RULE).append('\n');
        out.append("ETOPS\n").append(THIN_RULE).append('\n');
        out.append("  ").append(analysis.summary()).append('\n');
        out.append(String.format("  %-22s %s%n", "Adequate aerodromes",
                String.join(" ", analysis.adequateAerodromes())));
        analysis.entryPoint().ifPresent(point ->
                out.append(String.format("  %-22s %s%n", "Entry point",
                        CoordinateFormat.toArincDegreesMinutes(point))));
        analysis.exitPoint().ifPresent(point ->
                out.append(String.format("  %-22s %s%n", "Exit point",
                        CoordinateFormat.toArincDegreesMinutes(point))));
        out.append(String.format("  %-22s %s (%.0f min to %s)%n", "Critical point",
                CoordinateFormat.toArincDegreesMinutes(analysis.worstPoint()),
                analysis.worstDiversionMinutes(), analysis.worstNearestIcao()));

        if (!analysis.equalTimePoints().isEmpty()) {
            out.append('\n').append("  EQUAL TIME POINTS\n");
            for (EqualTimePoint etp : analysis.equalTimePoints()) {
                out.append(String.format("  %-14s %-19s %s  %.0f min each way%n",
                        etp.firstIcao() + "/" + etp.secondIcao(),
                        CoordinateFormat.toArincDegreesMinutes(etp.position()),
                        TIME.format(etp.reachedAt()),
                        etp.diversionTimeMinutes()));
            }
        }
    }

    private void alternates(StringBuilder out, OperationalFlightPlan plan) {
        out.append('\n').append(RULE).append('\n');
        out.append("ALTERNATES\n").append(THIN_RULE).append('\n');
        if (plan.alternates().isEmpty()) {
            out.append("  NONE HELD\n");
            return;
        }
        for (AlternateCandidate alternate : plan.alternates()) {
            out.append(String.format("  %-6s %5.0f nm %03.0f  %s%n",
                    alternate.icaoCode(), alternate.distanceNm(), alternate.bearingDeg(),
                    alternate.ceilingFt().isPresent()
                            ? "ceiling " + alternate.ceilingFt().getAsInt() + " ft"
                            : "no ceiling forecast"));
        }
    }

    private void release(StringBuilder out, OperationalFlightPlan plan) {
        out.append('\n').append(RULE).append('\n');
        if (plan.isDispatchable()) {
            out.append("DISPATCH RELEASE — no objections\n");
        } else {
            out.append("NOT DISPATCHABLE\n").append(THIN_RULE).append('\n');
            plan.dispatchObjections().forEach(objection -> {
                // Wrapped, not truncated: an objection is the reason a flight is not going, and
                // it has to be legible on the page it is printed on.
                String wrapped = wrap(objection, 74, "    ");
                out.append("  *").append(wrapped.substring(3)).append('\n');
            });
        }
        out.append(RULE).append('\n');
    }

    private static String abbreviate(FlightPhase phase) {
        return switch (phase) {
            case CLIMB -> "CLB";
            case CRUISE -> "CRZ";
            case STEP_CLIMB -> "STP";
            case DESCENT -> "DES";
        };
    }

    private static String formatMinutes(double minutes) {
        long total = Math.round(minutes);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static String formatDuration(Duration duration) {
        return String.format("%02d:%02d", duration.toHours(), duration.toMinutesPart());
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Wraps a route string onto continuation lines without breaking a token in half. */
    private static String wrap(String text, int width, String indent) {
        if (text == null || text.isBlank()) {
            return indent + "-";
        }
        StringBuilder wrapped = new StringBuilder();
        StringBuilder line = new StringBuilder(indent);
        for (String token : text.trim().split("\\s+")) {
            if (line.length() + token.length() + 1 > width && line.length() > indent.length()) {
                wrapped.append(line).append('\n');
                line = new StringBuilder(indent);
            }
            if (line.length() > indent.length()) {
                line.append(' ');
            }
            line.append(token);
        }
        return wrapped.append(line).toString();
    }

    /** Not used by the renderer, but the natural place to keep the instant formatter public. */
    public static String formatTime(Instant instant) {
        return TIME.format(instant);
    }
}
