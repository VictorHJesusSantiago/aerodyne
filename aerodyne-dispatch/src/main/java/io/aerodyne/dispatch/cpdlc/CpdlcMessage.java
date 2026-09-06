package io.aerodyne.dispatch.cpdlc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One controller-pilot datalink message.
 *
 * <p>A message carries its own sequence number and, when it is a reply, the number of the
 * message it answers. That pairing is the whole protocol: it is what lets either end know which
 * clearance a {@code WILCO} accepted when three are outstanding, and it is why a reply without
 * a reference is not a reply but a new message.
 *
 * @param messageId           this message's sequence number, unique per sender per session
 * @param referenceId         the message this one answers, or {@code null} when it starts an exchange
 * @param from                who sent it — a facility designator or an aircraft registration
 * @param to                  who it is for
 * @param sentAt              when it was sent
 * @param responseRequirement what kind of answer it demands
 * @param elements            the message elements, in order
 */
public record CpdlcMessage(int messageId,
                           Integer referenceId,
                           String from,
                           String to,
                           Instant sentAt,
                           ResponseRequirement responseRequirement,
                           List<CpdlcElement> elements) {

    public CpdlcMessage {
        if (messageId < 0) {
            throw new IllegalArgumentException("message id must not be negative");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("a message needs a sender and a recipient");
        }
        elements = List.copyOf(elements);
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("a message needs at least one element");
        }
    }

    /** A message that starts an exchange. */
    public static CpdlcMessage originating(int messageId, String from, String to, Instant sentAt,
                                           ResponseRequirement requirement,
                                           CpdlcElement... elements) {
        return new CpdlcMessage(messageId, null, from, to, sentAt, requirement, List.of(elements));
    }

    /** A message that answers another. */
    public static CpdlcMessage replyTo(CpdlcMessage original, int messageId, Instant sentAt,
                                       CpdlcElement... elements) {
        return new CpdlcMessage(messageId, original.messageId(), original.to(), original.from(),
                sentAt, ResponseRequirement.NONE, List.of(elements));
    }

    public OptionalInt reference() {
        return referenceId == null ? OptionalInt.empty() : OptionalInt.of(referenceId);
    }

    /** True when this message answers another. */
    public boolean isReply() {
        return referenceId != null;
    }

    /** True when every element travels from the ground to the aircraft. */
    public boolean isUplink() {
        return elements.stream().allMatch(CpdlcElement::isUplink);
    }

    /** True when every element travels from the aircraft to the ground. */
    public boolean isDownlink() {
        return elements.stream().allMatch(CpdlcElement::isDownlink);
    }

    /**
     * The response word this message carries, when it is a reply that carries one. A message
     * whose first element is {@code WILCO} answers with {@code WILCO}.
     */
    public Optional<String> responseWord() {
        return elements.stream()
                .map(CpdlcElement::asResponseWord)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** The message as the crew would read it on a display. */
    public String text() {
        return elements.stream().map(CpdlcElement::text)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /** True when any element is outside the catalogue this implementation understands. */
    public boolean hasUnknownElements() {
        return elements.stream().anyMatch(element -> !element.isKnown());
    }

    @Override
    public String toString() {
        return "[" + messageId + (isReply() ? " re " + referenceId : "") + "] "
                + from + " to " + to + ": " + text();
    }
}
