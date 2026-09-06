package io.aerodyne.dispatch.cpdlc;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes datalink messages on the wire.
 *
 * <p>The format is a single line of slash-separated fields:
 *
 * <pre>
 * CPDLC/1/12/-/EGLL_ATC/GABCD/2024-06-15T08:00:00Z/WU/UM20:FL350
 * CPDLC/1/47/12/GABCD/EGLL_ATC/2024-06-15T08:00:12Z/N/DM0
 * </pre>
 *
 * <p>The fields are: the protocol tag, the version, this message's number, the number it
 * answers ({@code -} when it answers nothing), the sender, the recipient, the time, the response
 * requirement, and the elements. Elements are separated by semicolons and each is a code with
 * its parameters after a colon, separated by pipes.
 *
 * <p>Real CPDLC runs over ACARS or ATN in a binary encoding, not text. A line-oriented text
 * format is used here because it is the part of the problem worth being explicit about — the
 * message structure, the reference pairing, the response semantics — and a bit-packed encoding
 * would obscure all three without changing any of them.
 *
 * <p>Decoding is strict. A malformed clearance is not something to interpret generously: the
 * failure mode of guessing is an aircraft climbing to a level nobody cleared it to.
 */
public final class CpdlcCodec {

    /** The tag every message starts with. */
    public static final String PROTOCOL_TAG = "CPDLC";
    /** The version this codec reads and writes. */
    public static final int VERSION = 1;

    private static final String FIELD_SEPARATOR = "/";
    private static final String ELEMENT_SEPARATOR = ";";
    private static final String PARAMETER_SEPARATOR = "|";
    private static final String NO_REFERENCE = "-";
    private static final int FIELD_COUNT = 9;

    /** Renders a message onto the wire. */
    public String encode(CpdlcMessage message) {
        StringBuilder encoded = new StringBuilder(96);
        encoded.append(PROTOCOL_TAG).append(FIELD_SEPARATOR)
                .append(VERSION).append(FIELD_SEPARATOR)
                .append(message.messageId()).append(FIELD_SEPARATOR)
                .append(message.referenceId() == null ? NO_REFERENCE : message.referenceId())
                .append(FIELD_SEPARATOR)
                .append(message.from()).append(FIELD_SEPARATOR)
                .append(message.to()).append(FIELD_SEPARATOR)
                .append(message.sentAt()).append(FIELD_SEPARATOR)
                .append(message.responseRequirement().code()).append(FIELD_SEPARATOR);

        for (int i = 0; i < message.elements().size(); i++) {
            if (i > 0) {
                encoded.append(ELEMENT_SEPARATOR);
            }
            encoded.append(encodeElement(message.elements().get(i)));
        }
        return encoded.toString();
    }

    /**
     * Reads a message off the wire.
     *
     * @throws CpdlcFormatException when the line is not a well-formed message
     */
    public CpdlcMessage decode(String line) {
        if (line == null || line.isBlank()) {
            throw new CpdlcFormatException("empty message", line);
        }
        // Limited so that a parameter containing a slash — a route string — survives the split.
        String[] fields = line.trim().split(FIELD_SEPARATOR, FIELD_COUNT);
        if (fields.length != FIELD_COUNT) {
            throw new CpdlcFormatException(
                    "expected " + FIELD_COUNT + " fields, found " + fields.length, line);
        }
        if (!PROTOCOL_TAG.equals(fields[0])) {
            throw new CpdlcFormatException("not a CPDLC message", line);
        }
        int version = parseInt(fields[1], "version", line);
        if (version != VERSION) {
            throw new CpdlcFormatException("unsupported protocol version " + version, line);
        }

        int messageId = parseInt(fields[2], "message id", line);
        Integer referenceId = NO_REFERENCE.equals(fields[3])
                ? null
                : parseInt(fields[3], "reference id", line);

        Instant sentAt;
        try {
            sentAt = Instant.parse(fields[6]);
        } catch (DateTimeParseException notATime) {
            throw new CpdlcFormatException("timestamp '" + fields[6] + "' is not a time", line);
        }

        ResponseRequirement requirement;
        try {
            requirement = ResponseRequirement.fromCode(fields[7]);
        } catch (IllegalArgumentException unknown) {
            throw new CpdlcFormatException(unknown.getMessage(), line);
        }

        List<CpdlcElement> elements = new ArrayList<>();
        for (String encoded : fields[8].split(ELEMENT_SEPARATOR)) {
            if (!encoded.isBlank()) {
                elements.add(decodeElement(encoded.trim(), line));
            }
        }
        if (elements.isEmpty()) {
            throw new CpdlcFormatException("no message elements", line);
        }

        try {
            return new CpdlcMessage(messageId, referenceId, fields[4], fields[5], sentAt,
                    requirement, elements);
        } catch (IllegalArgumentException invalid) {
            throw new CpdlcFormatException(invalid.getMessage(), line);
        }
    }

    /** Decodes several messages, one per line, skipping blank ones. */
    public List<CpdlcMessage> decodeAll(String transcript) {
        List<CpdlcMessage> messages = new ArrayList<>();
        for (String line : transcript.split("\\R")) {
            if (!line.isBlank()) {
                messages.add(decode(line));
            }
        }
        return List.copyOf(messages);
    }

    private static String encodeElement(CpdlcElement element) {
        if (element.parameters().isEmpty()) {
            return element.code();
        }
        return element.code() + ":" + String.join(PARAMETER_SEPARATOR, element.parameters());
    }

    private static CpdlcElement decodeElement(String encoded, String line) {
        int colon = encoded.indexOf(':');
        String code = colon < 0 ? encoded : encoded.substring(0, colon);
        List<String> parameters = colon < 0
                ? List.of()
                : List.of(encoded.substring(colon + 1).split("\\" + PARAMETER_SEPARATOR, -1));
        try {
            return new CpdlcElement(code, parameters);
        } catch (IllegalArgumentException invalid) {
            throw new CpdlcFormatException(invalid.getMessage(), line);
        }
    }

    private static int parseInt(String value, String what, String line) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new CpdlcFormatException(what + " '" + value + "' is not a number", line);
        }
    }

    /** Raised when a line is not a well-formed message. */
    public static class CpdlcFormatException extends RuntimeException {

        private final String line;

        public CpdlcFormatException(String message, String line) {
            super(message + ": '" + line + "'");
            this.line = line;
        }

        public String line() {
            return line;
        }
    }
}
