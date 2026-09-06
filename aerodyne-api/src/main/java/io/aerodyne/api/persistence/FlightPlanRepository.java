package io.aerodyne.api.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Stored flight plans. */
public interface FlightPlanRepository extends JpaRepository<FlightPlanEntity, Long> {

    /** Plans are addressed publicly by their reference, never by their primary key. */
    Optional<FlightPlanEntity> findByReference(UUID reference);

    Page<FlightPlanEntity> findByDepartureIcaoOrderByOffBlocksDesc(String departureIcao,
                                                                   Pageable pageable);

    Page<FlightPlanEntity> findByOffBlocksBetweenOrderByOffBlocks(Instant from, Instant to,
                                                                  Pageable pageable);

    /** The plans that were computed and refused, which is what a dispatch review looks at. */
    Page<FlightPlanEntity> findByDispatchableFalseOrderByComputedAtDesc(Pageable pageable);
}
