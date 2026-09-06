package io.aerodyne.dispatch.cpdlc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks an exchange of datalink messages and which of them are still awaiting an answer.
 *
 * <p>The state that matters is which uplinks are <em>open</em>. A clearance is not a clearance
 * until it has been accepted, and a controller with three outstanding uplinks needs to know
 * which one the {@code WILCO} that just arrived belongs to. That is what the reference number
 * settles and what this tracks.
 *
 * <p>{@code STANDBY} is the case worth being careful about. It is a valid reply and it does
 * <em>not</em> close the message: the aircraft is saying it has read the clearance and will
 * answer shortly. A dialogue that treated it as an answer would show an exchange as agreed when
 * nothing had been agreed at all.
 *
 * <p>Not thread-safe; a dialogue belongs to one session.
 */
public final class CpdlcDialogue {

    /** How long a message may stay open before it is worth chasing. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final Map<Integer, CpdlcMessage> open = new LinkedHashMap<>();
    private final Map<Integer, String> standingBy = new LinkedHashMap<>();
    private final List<CpdlcMessage> transcript = new ArrayList<>();
    private final List<String> protocolErrors = new ArrayList<>();
    private final Duration timeout;

    public CpdlcDialogue() {
        this(DEFAULT_TIMEOUT);
    }

    public CpdlcDialogue(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Records a message and updates what is outstanding.
     *
     * <p>A protocol error — a reply to a message nobody sent, a response the requirement does
     * not permit — is recorded rather than thrown. A datalink session that dropped a connection
     * over a malformed reply would be worse than one that logs it and keeps the link up.
     */
    public void record(CpdlcMessage message) {
        transcript.add(message);

        if (message.isReply()) {
            handleReply(message);
            return;
        }
        if (message.responseRequirement().requiresResponse()) {
            open.put(message.messageId(), message);
        }
    }

    private void handleReply(CpdlcMessage reply) {
        int reference = reply.referenceId();
        CpdlcMessage original = open.get(reference);
        if (original == null) {
            protocolErrors.add("message " + reply.messageId()
                    + " answers " + reference + ", which is not open");
            return;
        }
        Optional<String> word = reply.responseWord();
        if (word.isEmpty()) {
            // A reply carrying no response element leaves the message open: nothing was agreed.
            protocolErrors.add("message " + reply.messageId()
                    + " answers " + reference + " with no response element");
            return;
        }
        String response = word.get();
        if (!original.responseRequirement().permits(response)) {
            protocolErrors.add(response + " is not a valid answer to message " + reference
                    + ", which requires " + original.responseRequirement().code());
            return;
        }
        if (original.responseRequirement().isClosedBy(response)) {
            open.remove(reference);
            standingBy.remove(reference);
        } else {
            // STANDBY: read, not yet answered.
            standingBy.put(reference, response);
        }
    }

    /** The messages still awaiting an answer, in the order they were sent. */
    public List<CpdlcMessage> outstanding() {
        return List.copyOf(open.values());
    }

    /** True when nothing is awaiting an answer. */
    public boolean isSettled() {
        return open.isEmpty();
    }

    /** The messages that were acknowledged with {@code STANDBY} and never answered. */
    public List<CpdlcMessage> awaitingAfterStandby() {
        return standingBy.keySet().stream().map(open::get).filter(java.util.Objects::nonNull).toList();
    }

    /** The messages that have been open longer than the timeout at a given moment. */
    public List<CpdlcMessage> overdueAt(Instant now) {
        return open.values().stream()
                .filter(message -> Duration.between(message.sentAt(), now).compareTo(timeout) > 0)
                .toList();
    }

    /** Everything recorded, in order. */
    public List<CpdlcMessage> transcript() {
        return List.copyOf(transcript);
    }

    /** Every protocol violation seen, in order. */
    public List<String> protocolErrors() {
        return List.copyOf(protocolErrors);
    }

    /** The next message number to use, one past the highest seen. */
    public int nextMessageId() {
        return transcript.stream().mapToInt(CpdlcMessage::messageId).max().orElse(-1) + 1;
    }

    /** The reply that would close an open message, ready to be sent. */
    public Optional<CpdlcMessage> buildReply(int referenceId, CpdlcElement response, Instant at) {
        CpdlcMessage original = open.get(referenceId);
        if (original == null) {
            return Optional.empty();
        }
        return Optional.of(CpdlcMessage.replyTo(original, nextMessageId(), at, response));
    }
}
