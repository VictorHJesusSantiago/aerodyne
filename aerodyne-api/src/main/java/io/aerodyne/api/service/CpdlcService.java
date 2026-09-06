package io.aerodyne.api.service;

import io.aerodyne.dispatch.cpdlc.CpdlcCodec;
import io.aerodyne.dispatch.cpdlc.CpdlcDialogue;
import io.aerodyne.dispatch.cpdlc.CpdlcElement;
import io.aerodyne.dispatch.cpdlc.CpdlcMessage;
import io.aerodyne.dispatch.cpdlc.ResponseRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datalink sessions, one per aircraft.
 *
 * <p>A session is a conversation with state: which clearances are outstanding, which have been
 * acknowledged with {@code STANDBY} and not yet answered, and what number the next message
 * takes. That state belongs to the pair of parties talking, not to a request, so it is held
 * here for as long as the aircraft is connected.
 *
 * <p>Sessions are keyed by the aircraft's identifier and created on first contact. Ending one is
 * explicit: a session that vanished on its own would lose the record of an exchange that a crew
 * may still be acting on.
 *
 * <p>Every message is also handed to a {@link CpdlcArchive}, which keeps it. The two are not the
 * same thing: the session holds what is still outstanding and is meaningless once the aircraft
 * lands, while the archive holds what was said and is what an investigation reads afterwards.
 */
@Service
public class CpdlcService {

    private final Map<String, CpdlcDialogue> sessions = new ConcurrentHashMap<>();
    private final CpdlcCodec codec = new CpdlcCodec();
    private final CpdlcArchive archive;

    /**
     * Marked as the one to inject. With two constructors and neither annotated, Spring picks
     * the no-argument one, the archive silently becomes the no-op, and nothing is ever
     * recorded — which looks exactly like a working system until somebody asks for a transcript.
     */
    @Autowired
    public CpdlcService(CpdlcArchive archive) {
        this.archive = archive;
    }

    /** A service that keeps no record, for tests of the protocol itself. */
    public CpdlcService() {
        this(CpdlcArchive.NONE);
    }

    /** The session for an aircraft, opening one if this is first contact. */
    public CpdlcDialogue session(String aircraft) {
        return sessions.computeIfAbsent(aircraft.toUpperCase(java.util.Locale.ROOT),
                key -> new CpdlcDialogue());
    }

    /** The session for an aircraft, without opening one. */
    public Optional<CpdlcDialogue> existingSession(String aircraft) {
        return Optional.ofNullable(sessions.get(aircraft.toUpperCase(java.util.Locale.ROOT)));
    }

    /** The aircraft currently connected. */
    public List<String> connected() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * Records a message that arrived on the wire.
     *
     * <p>The session is chosen by whichever party is the aircraft. A message from the ground is
     * filed under its recipient and one from the aircraft under its sender, so both halves of an
     * exchange land in the same session.
     */
    public CpdlcMessage receive(String aircraft, String wireFormat) {
        CpdlcMessage message = codec.decode(wireFormat);
        session(aircraft).record(message);
        archive.record(message, wireFormat);
        return message;
    }

    /** Sends a clearance or a request, numbering it from the session. */
    public CpdlcMessage send(String aircraft, String from, String to,
                             ResponseRequirement requirement, List<CpdlcElement> elements,
                             Instant at) {
        CpdlcDialogue dialogue = session(aircraft);
        CpdlcMessage message = new CpdlcMessage(dialogue.nextMessageId(), null, from, to, at,
                requirement, elements);
        dialogue.record(message);
        archive.record(message, codec.encode(message));
        return message;
    }

    /**
     * Answers an outstanding message.
     *
     * @return the reply that was sent, or empty when nothing with that reference is open
     */
    public Optional<CpdlcMessage> reply(String aircraft, int referenceId, CpdlcElement response,
                                        Instant at) {
        CpdlcDialogue dialogue = session(aircraft);
        Optional<CpdlcMessage> reply = dialogue.buildReply(referenceId, response, at);
        reply.ifPresent(message -> {
            dialogue.record(message);
            archive.record(message, codec.encode(message));
        });
        return reply;
    }

    /** Renders a message onto the wire. */
    public String encode(CpdlcMessage message) {
        return codec.encode(message);
    }

    /** Ends a session and returns its transcript. */
    public List<CpdlcMessage> end(String aircraft) {
        CpdlcDialogue dialogue = sessions.remove(aircraft.toUpperCase(java.util.Locale.ROOT));
        return dialogue == null ? List.of() : dialogue.transcript();
    }
}
