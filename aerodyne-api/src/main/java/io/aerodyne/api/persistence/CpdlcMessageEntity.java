package io.aerodyne.api.persistence;

import io.aerodyne.dispatch.cpdlc.CpdlcMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One datalink message, stored.
 *
 * <p>Kept so that an exchange can be reconstructed after the fact, which is the only reason to
 * store it: the live state of a dialogue — what is outstanding, what was acknowledged with
 * standby — lives in memory, because it is only meaningful while the aircraft is connected.
 *
 * <p>The wire format is stored verbatim alongside the decoded fields. Decoding is this system's
 * interpretation of what was sent; the line is what was actually sent, and an investigation
 * wants the second, not the first.
 */
@Entity(name = "CpdlcMessageEntity")
@Table(name = "cpdlc_message")
public class CpdlcMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The flight this belonged to, when it is known; a message may arrive before a plan exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_plan_id")
    private FlightPlanEntity flightPlan;

    @Column(name = "message_id", nullable = false)
    private int messageId;

    /** The message this one answers; what pairs a WILCO with the clearance it accepted. */
    @Column(name = "reference_id")
    private Integer referenceId;

    @Column(nullable = false, length = 16)
    private String sender;

    @Column(nullable = false, length = 16)
    private String recipient;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "response_requirement", nullable = false, length = 2)
    private String responseRequirement;

    @Column(name = "wire_format", nullable = false)
    private String wireFormat;

    protected CpdlcMessageEntity() {
    }

    public static CpdlcMessageEntity from(CpdlcMessage message, String wireFormat,
                                          FlightPlanEntity flightPlan) {
        CpdlcMessageEntity entity = new CpdlcMessageEntity();
        entity.flightPlan = flightPlan;
        entity.messageId = message.messageId();
        entity.referenceId = message.referenceId();
        entity.sender = message.from();
        entity.recipient = message.to();
        entity.sentAt = message.sentAt();
        entity.responseRequirement = message.responseRequirement().code();
        entity.wireFormat = wireFormat;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public int getMessageId() {
        return messageId;
    }

    public Integer getReferenceId() {
        return referenceId;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getWireFormat() {
        return wireFormat;
    }
}
