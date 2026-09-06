package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Stored datalink messages. */
public interface CpdlcMessageRepository extends JpaRepository<CpdlcMessageEntity, Long> {

    /** One aircraft's side of an exchange, oldest first. */
    List<CpdlcMessageEntity> findBySenderOrRecipientOrderBySentAt(String sender, String recipient);

    /** Everything said in a window, for reconstructing an event. */
    List<CpdlcMessageEntity> findBySentAtBetweenOrderBySentAt(Instant from, Instant to);
}
