package io.aerodyne.dispatch.cpdlc;

import java.util.List;

/**
 * What kind of answer a controller-pilot message demands.
 *
 * <p>This is the part of a datalink protocol that carries the operational meaning. An uplink
 * marked {@code WU} is a clearance: the only valid answers are to accept it or to say you
 * cannot, and until one of those arrives the clearance is not agreed. One marked {@code R} is
 * information, and one marked {@code N} closes the exchange. Getting this wrong is how a
 * dialogue ends up with each side believing the other has agreed to something.
 */
public enum ResponseRequirement {

    /** {@code W/U}: wilco or unable. A clearance. */
    WILCO_UNABLE("WU", List.of("WILCO", "UNABLE", "STANDBY")),
    /** {@code A/N}: affirm or negative. A question. */
    AFFIRM_NEGATIVE("AN", List.of("AFFIRM", "NEGATIVE", "STANDBY")),
    /** {@code R}: acknowledgement only. */
    ROGER("R", List.of("ROGER", "UNABLE", "STANDBY")),
    /** {@code Y}: any response element is valid. */
    ANY("Y", List.of()),
    /** {@code N}: no response required; the message closes the exchange. */
    NONE("N", List.of());

    private final String code;
    private final List<String> permittedResponses;

    ResponseRequirement(String code, List<String> permittedResponses) {
        this.code = code;
        this.permittedResponses = permittedResponses;
    }

    public String code() {
        return code;
    }

    /** The responses this requirement accepts; empty when anything or nothing is valid. */
    public List<String> permittedResponses() {
        return permittedResponses;
    }

    /** True when the message stays open until an answer arrives. */
    public boolean requiresResponse() {
        return this != NONE;
    }

    /**
     * True when a response element closes the exchange. {@code STANDBY} does not: it is a valid
     * reply that leaves the message open, which is exactly the case a naive implementation gets
     * wrong.
     */
    public boolean isClosedBy(String responseElement) {
        if (this == NONE) {
            return false;
        }
        if ("STANDBY".equalsIgnoreCase(responseElement)) {
            return false;
        }
        return this == ANY || permittedResponses.stream().anyMatch(r -> r.equalsIgnoreCase(responseElement));
    }

    /** True when a response element is one this requirement permits at all. */
    public boolean permits(String responseElement) {
        return this == ANY
                || permittedResponses.stream().anyMatch(r -> r.equalsIgnoreCase(responseElement));
    }

    public static ResponseRequirement fromCode(String code) {
        for (ResponseRequirement requirement : values()) {
            if (requirement.code.equalsIgnoreCase(code)) {
                return requirement;
            }
        }
        throw new IllegalArgumentException("unknown response requirement: '" + code + "'");
    }
}
