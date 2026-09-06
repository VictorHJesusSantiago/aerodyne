package io.aerodyne.dispatch.cpdlc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One message element: a standard phrase with its parameters filled in.
 *
 * <p>Controller-pilot datalink does not carry free text for anything operational. Every
 * clearance, request and reply is a numbered element from a fixed catalogue — {@code UM20} is
 * "climb to and maintain [level]" and nothing else — and the parameters are the only variable
 * part. That is what makes the exchange unambiguous across languages and what lets an aircraft
 * system load a clearance without anyone reading it.
 *
 * @param code       the element identifier: {@code UM} for uplink, {@code DM} for downlink
 * @param parameters the values filling the element's placeholders, in order
 */
public record CpdlcElement(String code, List<String> parameters) {

    /** The elements this implementation knows, and the text each one stands for. */
    private static final Map<String, String> CATALOGUE = Map.ofEntries(
            // Uplink: controller to aircraft.
            Map.entry("UM0", "UNABLE"),
            Map.entry("UM1", "STANDBY"),
            Map.entry("UM3", "ROGER"),
            Map.entry("UM4", "AFFIRM"),
            Map.entry("UM5", "NEGATIVE"),
            Map.entry("UM19", "MAINTAIN %s"),
            Map.entry("UM20", "CLIMB TO AND MAINTAIN %s"),
            Map.entry("UM23", "DESCEND TO AND MAINTAIN %s"),
            Map.entry("UM74", "PROCEED DIRECT TO %s"),
            Map.entry("UM80", "CLEARED %s"),
            Map.entry("UM106", "MAINTAIN %s"),
            Map.entry("UM120", "CONTACT %s %s"),
            Map.entry("UM169", "%s"),
            // Downlink: aircraft to controller.
            Map.entry("DM0", "WILCO"),
            Map.entry("DM1", "UNABLE"),
            Map.entry("DM2", "STANDBY"),
            Map.entry("DM3", "ROGER"),
            Map.entry("DM4", "AFFIRM"),
            Map.entry("DM5", "NEGATIVE"),
            Map.entry("DM6", "REQUEST %s"),
            Map.entry("DM9", "REQUEST CLIMB TO %s"),
            Map.entry("DM10", "REQUEST DESCENT TO %s"),
            Map.entry("DM22", "REQUEST DIRECT TO %s"),
            Map.entry("DM48", "POSITION REPORT %s"),
            Map.entry("DM67", "%s"));

    public CpdlcElement {
        if (code == null || !code.matches("[UD]M\\d+")) {
            throw new IllegalArgumentException("not a message element identifier: '" + code + "'");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public static CpdlcElement of(String code, String... parameters) {
        return new CpdlcElement(code, List.of(parameters));
    }

    /** True for an element sent from the ground to the aircraft. */
    public boolean isUplink() {
        return code.startsWith("UM");
    }

    /** True for one sent from the aircraft to the ground. */
    public boolean isDownlink() {
        return code.startsWith("DM");
    }

    /** True when the element is in the catalogue this implementation understands. */
    public boolean isKnown() {
        return CATALOGUE.containsKey(code);
    }

    /**
     * The element as the phrase it stands for, with its parameters substituted.
     *
     * <p>An element outside the catalogue renders as its code and parameters rather than
     * failing. A datalink implementation that dropped an unrecognised element would leave the
     * crew unaware that anything had been sent, which is worse than showing them a code.
     */
    public String text() {
        String template = CATALOGUE.get(code);
        if (template == null) {
            return code + (parameters.isEmpty() ? "" : " " + String.join(" ", parameters));
        }
        long placeholders = template.chars().filter(c -> c == '%').count();
        if (placeholders == 0) {
            return template;
        }
        Object[] arguments = new Object[(int) placeholders];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = i < parameters.size() ? parameters.get(i) : "[?]";
        }
        return String.format(Locale.ROOT, template, arguments);
    }

    /**
     * The bare response word, for an element that is one — {@code WILCO}, {@code UNABLE},
     * {@code STANDBY}. Empty for anything else.
     */
    public java.util.Optional<String> asResponseWord() {
        return switch (code) {
            case "DM0", "UM0", "DM1", "DM2", "DM3", "DM4", "DM5",
                 "UM1", "UM3", "UM4", "UM5" -> java.util.Optional.of(text());
            default -> java.util.Optional.empty();
        };
    }

    @Override
    public String toString() {
        return text();
    }
}
