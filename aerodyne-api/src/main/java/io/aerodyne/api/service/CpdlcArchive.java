package io.aerodyne.api.service;

import io.aerodyne.api.persistence.CpdlcMessageEntity;
import io.aerodyne.api.persistence.CpdlcMessageRepository;
import io.aerodyne.dispatch.cpdlc.CpdlcMessage;
import org.springframework.stereotype.Component;

/**
 * Keeps a copy of every datalink message that passes through.
 *
 * <p>A narrow interface rather than the repository itself, for two reasons. The live dialogue
 * has no business knowing about JPA — it is protocol state, not storage — and a test of the
 * protocol should not need a database to run. {@link #NONE} is what those tests use.
 *
 * <p>The distinction it draws is worth being explicit about: the dialogue holds what is
 * <em>outstanding</em>, which matters only while the aircraft is connected and is meaningless
 * once it lands. The archive holds what was <em>said</em>, which matters afterwards and is the
 * only part worth a table.
 */
public interface CpdlcArchive {

    /** An archive that keeps nothing, for tests and for a session nobody is recording. */
    CpdlcArchive NONE = (message, wireFormat) -> {
    };

    /**
     * Records one message.
     *
     * @param wireFormat the line as it went on the wire, kept verbatim: decoding is this
     *                   system's reading of what was sent, and an investigation wants what was
     *                   actually sent
     */
    void record(CpdlcMessage message, String wireFormat);

    /** The archive that writes to the database. */
    @Component
    class Persistent implements CpdlcArchive {

        private final CpdlcMessageRepository messages;

        public Persistent(CpdlcMessageRepository messages) {
            this.messages = messages;
        }

        @Override
        public void record(CpdlcMessage message, String wireFormat) {
            // No flight plan is attached: a message can arrive before a plan exists for the
            // sector, and tying the two together is a separate job from keeping the record.
            messages.save(CpdlcMessageEntity.from(message, wireFormat, null));
        }
    }
}
