package io.aerodyne.api.web;

import io.aerodyne.api.service.CpdlcService;
import io.aerodyne.dispatch.cpdlc.CpdlcDialogue;
import io.aerodyne.dispatch.cpdlc.CpdlcElement;
import io.aerodyne.dispatch.cpdlc.CpdlcMessage;
import io.aerodyne.dispatch.cpdlc.ResponseRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Controller-pilot datalink.
 *
 * <p>Two ways in, because there are two kinds of caller. A ground system that already speaks the
 * wire format posts the encoded line to {@code /received}; a dispatcher or a test harness builds
 * a message from its elements and posts that. Both land in the same session and both are subject
 * to the same protocol rules.
 *
 * <p>The endpoint worth reading is {@code /outstanding}. A clearance that has been sent and not
 * answered is the state that matters operationally — it is a clearance nobody has agreed to —
 * and it is the thing a controller's display has to show.
 */
@RestController
@RequestMapping("/api/cpdlc")
public class CpdlcController {

    private final CpdlcService cpdlc;

    public CpdlcController(CpdlcService cpdlc) {
        this.cpdlc = cpdlc;
    }

    /** The aircraft currently connected. */
    @GetMapping("/sessions")
    public List<String> sessions() {
        return cpdlc.connected();
    }

    /** Sends a message to an aircraft, or from one. */
    @PostMapping("/{aircraft}/messages")
    public MessageView send(@PathVariable String aircraft, @Valid @RequestBody SendRequest request) {
        List<CpdlcElement> elements = request.elements().stream()
                .map(element -> new CpdlcElement(element.code(), element.parameters()))
                .toList();
        CpdlcMessage message = cpdlc.send(aircraft, request.from(), request.to(),
                ResponseRequirement.fromCode(request.responseRequirement()), elements,
                Instant.now());
        return MessageView.of(message, cpdlc.encode(message));
    }

    /** Records a message that arrived already encoded. */
    @PostMapping(value = "/{aircraft}/received", consumes = MediaType.TEXT_PLAIN_VALUE)
    public MessageView receive(@PathVariable String aircraft, @RequestBody String wireFormat) {
        CpdlcMessage message = cpdlc.receive(aircraft, wireFormat);
        return MessageView.of(message, cpdlc.encode(message));
    }

    /** Answers an outstanding message. */
    @PostMapping("/{aircraft}/messages/{reference}/reply")
    public MessageView reply(@PathVariable String aircraft, @PathVariable int reference,
                             @Valid @RequestBody ReplyRequest request) {
        CpdlcMessage message = cpdlc
                .reply(aircraft, reference,
                        new CpdlcElement(request.code(), request.parameters()), Instant.now())
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException(
                        "open message", String.valueOf(reference)));
        return MessageView.of(message, cpdlc.encode(message));
    }

    /** What is awaiting an answer, and anything that broke the protocol. */
    @GetMapping("/{aircraft}/outstanding")
    public SessionView outstanding(@PathVariable String aircraft) {
        CpdlcDialogue dialogue = cpdlc.existingSession(aircraft)
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("session", aircraft));

        return new SessionView(aircraft,
                dialogue.outstanding().stream()
                        .map(message -> MessageView.of(message, cpdlc.encode(message)))
                        .toList(),
                dialogue.awaitingAfterStandby().stream().map(CpdlcMessage::messageId).toList(),
                dialogue.overdueAt(Instant.now()).stream().map(CpdlcMessage::messageId).toList(),
                dialogue.protocolErrors(),
                dialogue.isSettled());
    }

    /** The whole exchange so far. */
    @GetMapping("/{aircraft}/transcript")
    public List<MessageView> transcript(@PathVariable String aircraft) {
        return cpdlc.existingSession(aircraft)
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("session", aircraft))
                .transcript().stream()
                .map(message -> MessageView.of(message, cpdlc.encode(message)))
                .toList();
    }

    /** Ends a session and returns what was said. */
    @PostMapping("/{aircraft}/end")
    public List<MessageView> end(@PathVariable String aircraft) {
        return cpdlc.end(aircraft).stream()
                .map(message -> MessageView.of(message, cpdlc.encode(message)))
                .toList();
    }

    /** A message to send. */
    public record SendRequest(@NotBlank String from,
                              @NotBlank String to,
                              @NotBlank String responseRequirement,
                              @NotEmpty List<ElementRequest> elements) {
    }

    /** One element of it. */
    public record ElementRequest(@NotBlank String code, List<String> parameters) {

        public ElementRequest {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /** An answer to an outstanding message. */
    public record ReplyRequest(@NotBlank String code, List<String> parameters) {

        public ReplyRequest {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /** A message, both as text a person reads and as the line that went on the wire. */
    public record MessageView(int messageId, Integer referenceId, String from, String to,
                              Instant sentAt, String responseRequirement, String text,
                              String wireFormat, boolean hasUnknownElements) {

        static MessageView of(CpdlcMessage message, String wireFormat) {
            return new MessageView(message.messageId(), message.referenceId(), message.from(),
                    message.to(), message.sentAt(), message.responseRequirement().code(),
                    message.text(), wireFormat, message.hasUnknownElements());
        }
    }

    /** The state of one session. */
    public record SessionView(String aircraft, List<MessageView> outstanding,
                              List<Integer> acknowledgedWithStandby, List<Integer> overdue,
                              List<String> protocolErrors, boolean settled) {
    }
}
