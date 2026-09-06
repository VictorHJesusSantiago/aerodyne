package io.aerodyne.dispatch.cpdlc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpdlcTest {

    private static final Instant T0 = Instant.parse("2024-06-15T08:00:00Z");
    private static final String ATC = "EGLL_ATC";
    private static final String AIRCRAFT = "GABCD";

    private final CpdlcCodec codec = new CpdlcCodec();

    private CpdlcMessage clearance(int id, Instant at) {
        return CpdlcMessage.originating(id, ATC, AIRCRAFT, at,
                ResponseRequirement.WILCO_UNABLE, CpdlcElement.of("UM20", "FL350"));
    }

    @Nested
    @DisplayName("message elements")
    class Elements {

        @Test
        void a_catalogued_element_renders_as_its_phrase() {
            assertThat(CpdlcElement.of("UM20", "FL350").text())
                    .isEqualTo("CLIMB TO AND MAINTAIN FL350");
            assertThat(CpdlcElement.of("DM0").text()).isEqualTo("WILCO");
            assertThat(CpdlcElement.of("UM74", "KOLIN").text())
                    .isEqualTo("PROCEED DIRECT TO KOLIN");
        }

        @Test
        void an_element_with_two_parameters_fills_both() {
            assertThat(CpdlcElement.of("UM120", "LONDON CONTROL", "127.100").text())
                    .isEqualTo("CONTACT LONDON CONTROL 127.100");
        }

        @Test
        void a_missing_parameter_is_shown_as_missing_rather_than_dropped() {
            assertThat(CpdlcElement.of("UM20").text())
                    .isEqualTo("CLIMB TO AND MAINTAIN [?]");
        }

        @Test
        void an_uncatalogued_element_shows_its_code_rather_than_disappearing() {
            CpdlcElement unknown = CpdlcElement.of("UM999", "SOMETHING");

            assertThat(unknown.isKnown()).isFalse();
            assertThat(unknown.text()).isEqualTo("UM999 SOMETHING");
        }

        @Test
        void the_prefix_says_which_way_the_element_travels() {
            assertThat(CpdlcElement.of("UM20", "FL350").isUplink()).isTrue();
            assertThat(CpdlcElement.of("DM0").isDownlink()).isTrue();
        }

        @Test
        void refuses_something_that_is_not_an_element_identifier() {
            assertThatThrownBy(() -> CpdlcElement.of("CLIMB"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a message element identifier");
        }
    }

    @Nested
    @DisplayName("the wire format")
    class Codec {

        @Test
        void encodes_a_clearance() {
            assertThat(codec.encode(clearance(12, T0)))
                    .isEqualTo("CPDLC/1/12/-/EGLL_ATC/GABCD/2024-06-15T08:00:00Z/WU/UM20:FL350");
        }

        @Test
        void encodes_a_reply_with_the_message_it_answers() {
            CpdlcMessage wilco = CpdlcMessage.replyTo(clearance(12, T0), 47,
                    T0.plusSeconds(12), CpdlcElement.of("DM0"));

            assertThat(codec.encode(wilco))
                    .isEqualTo("CPDLC/1/47/12/GABCD/EGLL_ATC/2024-06-15T08:00:12Z/N/DM0");
        }

        @Test
        void round_trips_a_message_through_the_wire() {
            CpdlcMessage original = clearance(12, T0);

            assertThat(codec.decode(codec.encode(original))).isEqualTo(original);
        }

        @Test
        void round_trips_a_multi_element_message() {
            CpdlcMessage original = CpdlcMessage.originating(3, ATC, AIRCRAFT, T0,
                    ResponseRequirement.WILCO_UNABLE,
                    CpdlcElement.of("UM74", "BEDEK"),
                    CpdlcElement.of("UM20", "FL370"));

            CpdlcMessage decoded = codec.decode(codec.encode(original));

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.text()).isEqualTo("PROCEED DIRECT TO BEDEK CLIMB TO AND MAINTAIN FL370");
        }

        @Test
        void a_parameter_containing_a_slash_survives_the_encoding() {
            CpdlcMessage original = CpdlcMessage.originating(5, ATC, AIRCRAFT, T0,
                    ResponseRequirement.ROGER,
                    CpdlcElement.of("UM80", "KOLIN UN862 BEDEK/N0450F350"));

            assertThat(codec.decode(codec.encode(original))).isEqualTo(original);
        }

        @Test
        void decodes_several_messages_from_a_transcript() {
            String transcript = codec.encode(clearance(1, T0)) + "\n\n"
                    + codec.encode(CpdlcMessage.replyTo(clearance(1, T0), 2,
                    T0.plusSeconds(5), CpdlcElement.of("DM0")));

            assertThat(codec.decodeAll(transcript)).hasSize(2);
        }

        @Test
        void refuses_a_line_that_is_not_a_message() {
            assertThatThrownBy(() -> codec.decode("CLIMB TO FL350"))
                    .isInstanceOf(CpdlcCodec.CpdlcFormatException.class)
                    .hasMessageContaining("expected 9 fields");
        }

        @Test
        void refuses_a_protocol_version_it_does_not_know() {
            assertThatThrownBy(() -> codec.decode(
                    "CPDLC/9/12/-/EGLL_ATC/GABCD/2024-06-15T08:00:00Z/WU/UM20:FL350"))
                    .isInstanceOf(CpdlcCodec.CpdlcFormatException.class)
                    .hasMessageContaining("unsupported protocol version 9");
        }

        @Test
        void refuses_a_malformed_timestamp_rather_than_guessing_at_it() {
            assertThatThrownBy(() -> codec.decode(
                    "CPDLC/1/12/-/EGLL_ATC/GABCD/yesterday/WU/UM20:FL350"))
                    .isInstanceOf(CpdlcCodec.CpdlcFormatException.class)
                    .hasMessageContaining("is not a time");
        }

        @Test
        void refuses_a_message_with_no_elements() {
            assertThatThrownBy(() -> codec.decode(
                    "CPDLC/1/12/-/EGLL_ATC/GABCD/2024-06-15T08:00:00Z/WU/"))
                    .isInstanceOf(CpdlcCodec.CpdlcFormatException.class)
                    .hasMessageContaining("no message elements");
        }

        @Test
        void refuses_an_unknown_response_requirement() {
            assertThatThrownBy(() -> codec.decode(
                    "CPDLC/1/12/-/EGLL_ATC/GABCD/2024-06-15T08:00:00Z/ZZ/UM20:FL350"))
                    .isInstanceOf(CpdlcCodec.CpdlcFormatException.class)
                    .hasMessageContaining("unknown response requirement");
        }
    }

    @Nested
    @DisplayName("dialogue state")
    class Dialogue {

        @Test
        void a_clearance_stays_open_until_it_is_answered() {
            CpdlcDialogue dialogue = new CpdlcDialogue();

            dialogue.record(clearance(1, T0));
            assertThat(dialogue.isSettled()).isFalse();
            assertThat(dialogue.outstanding()).hasSize(1);

            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 2,
                    T0.plusSeconds(10), CpdlcElement.of("DM0")));
            assertThat(dialogue.isSettled()).isTrue();
        }

        @Test
        void UNABLE_closes_a_clearance_just_as_WILCO_does() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(1, T0));

            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 2,
                    T0.plusSeconds(10), CpdlcElement.of("DM1")));

            assertThat(dialogue.isSettled()).isTrue();
            assertThat(dialogue.protocolErrors()).isEmpty();
        }

        @Test
        void STANDBY_does_not_close_the_clearance() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(1, T0));

            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 2,
                    T0.plusSeconds(5), CpdlcElement.of("DM2")));

            assertThat(dialogue.isSettled()).isFalse();
            assertThat(dialogue.awaitingAfterStandby()).hasSize(1);
            assertThat(dialogue.protocolErrors()).isEmpty();
        }

        @Test
        void a_WILCO_after_a_STANDBY_finally_closes_it() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(1, T0));
            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 2,
                    T0.plusSeconds(5), CpdlcElement.of("DM2")));

            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 3,
                    T0.plusSeconds(40), CpdlcElement.of("DM0")));

            assertThat(dialogue.isSettled()).isTrue();
            assertThat(dialogue.awaitingAfterStandby()).isEmpty();
        }

        @Test
        void tracks_several_open_clearances_by_their_reference_numbers() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            CpdlcMessage first = clearance(1, T0);
            CpdlcMessage second = CpdlcMessage.originating(2, ATC, AIRCRAFT, T0.plusSeconds(30),
                    ResponseRequirement.WILCO_UNABLE, CpdlcElement.of("UM74", "BEDEK"));
            dialogue.record(first);
            dialogue.record(second);

            dialogue.record(CpdlcMessage.replyTo(second, 3, T0.plusSeconds(35),
                    CpdlcElement.of("DM0")));

            assertThat(dialogue.outstanding()).extracting(CpdlcMessage::messageId)
                    .containsExactly(1);
        }

        @Test
        void a_message_that_needs_no_answer_never_becomes_outstanding() {
            CpdlcDialogue dialogue = new CpdlcDialogue();

            dialogue.record(CpdlcMessage.originating(1, ATC, AIRCRAFT, T0,
                    ResponseRequirement.NONE, CpdlcElement.of("UM3")));

            assertThat(dialogue.isSettled()).isTrue();
        }

        @Test
        void records_a_reply_to_a_message_nobody_sent_as_a_protocol_error() {
            CpdlcDialogue dialogue = new CpdlcDialogue();

            dialogue.record(CpdlcMessage.replyTo(clearance(99, T0), 1, T0,
                    CpdlcElement.of("DM0")));

            assertThat(dialogue.protocolErrors())
                    .anyMatch(error -> error.contains("answers 99, which is not open"));
        }

        @Test
        void records_an_answer_the_requirement_does_not_permit() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            CpdlcMessage question = CpdlcMessage.originating(1, ATC, AIRCRAFT, T0,
                    ResponseRequirement.AFFIRM_NEGATIVE, CpdlcElement.of("UM169", "ABLE FL390"));
            dialogue.record(question);

            dialogue.record(CpdlcMessage.replyTo(question, 2, T0.plusSeconds(5),
                    CpdlcElement.of("DM0")));

            assertThat(dialogue.protocolErrors())
                    .anyMatch(error -> error.contains("WILCO is not a valid answer"));
            assertThat(dialogue.isSettled()).isFalse();
        }

        @Test
        void a_reply_carrying_no_response_element_leaves_the_message_open() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(1, T0));

            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 2, T0.plusSeconds(5),
                    CpdlcElement.of("DM6", "FL390")));

            assertThat(dialogue.isSettled()).isFalse();
            assertThat(dialogue.protocolErrors())
                    .anyMatch(error -> error.contains("no response element"));
        }

        @Test
        void reports_the_clearances_that_have_gone_unanswered_too_long() {
            CpdlcDialogue dialogue = new CpdlcDialogue(Duration.ofMinutes(2));
            dialogue.record(clearance(1, T0));

            assertThat(dialogue.overdueAt(T0.plusSeconds(60))).isEmpty();
            assertThat(dialogue.overdueAt(T0.plusSeconds(200)))
                    .extracting(CpdlcMessage::messageId).containsExactly(1);
        }

        @Test
        void builds_the_reply_that_would_close_an_open_message() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(7, T0));

            CpdlcMessage reply = dialogue.buildReply(7, CpdlcElement.of("DM0"), T0.plusSeconds(9))
                    .orElseThrow();

            assertThat(reply.reference()).hasValue(7);
            assertThat(reply.from()).isEqualTo(AIRCRAFT);
            assertThat(reply.to()).isEqualTo(ATC);
            assertThat(reply.responseWord()).contains("WILCO");
        }

        @Test
        void cannot_build_a_reply_to_something_that_is_not_open() {
            assertThat(new CpdlcDialogue().buildReply(7, CpdlcElement.of("DM0"), T0)).isEmpty();
        }

        @Test
        void keeps_the_whole_exchange_and_numbers_the_next_message() {
            CpdlcDialogue dialogue = new CpdlcDialogue();
            dialogue.record(clearance(1, T0));
            dialogue.record(CpdlcMessage.replyTo(clearance(1, T0), 5, T0.plusSeconds(5),
                    CpdlcElement.of("DM0")));

            assertThat(dialogue.transcript()).hasSize(2);
            assertThat(dialogue.nextMessageId()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("response requirements")
    class Requirements {

        @Test
        void a_clearance_accepts_only_wilco_unable_or_standby() {
            ResponseRequirement requirement = ResponseRequirement.WILCO_UNABLE;

            assertThat(requirement.permits("WILCO")).isTrue();
            assertThat(requirement.permits("UNABLE")).isTrue();
            assertThat(requirement.permits("STANDBY")).isTrue();
            assertThat(requirement.permits("AFFIRM")).isFalse();
        }

        @Test
        void standby_never_closes_an_exchange() {
            for (ResponseRequirement requirement : ResponseRequirement.values()) {
                assertThat(requirement.isClosedBy("STANDBY"))
                        .as("%s closed by STANDBY", requirement)
                        .isFalse();
            }
        }

        @Test
        void a_message_needing_no_answer_is_closed_by_nothing() {
            assertThat(ResponseRequirement.NONE.requiresResponse()).isFalse();
            assertThat(ResponseRequirement.NONE.isClosedBy("WILCO")).isFalse();
        }

        @Test
        void reads_and_writes_its_wire_code() {
            for (ResponseRequirement requirement : ResponseRequirement.values()) {
                assertThat(ResponseRequirement.fromCode(requirement.code())).isEqualTo(requirement);
            }
        }
    }
}
