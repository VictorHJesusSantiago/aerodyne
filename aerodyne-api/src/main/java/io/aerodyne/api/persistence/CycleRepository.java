package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** AIRAC cycles. */
public interface CycleRepository extends JpaRepository<NavigationEntities.CycleEntity, Long> {

    Optional<NavigationEntities.CycleEntity> findByIdentifier(String identifier);

    /**
     * The cycle in force on a date. Cycles do not overlap, so at most one matches.
     */
    @Query("""
            select c from CycleEntity c
            where c.effectiveDate <= :date and c.expiryDate > :date
            """)
    Optional<NavigationEntities.CycleEntity> findEffectiveOn(
            @Param("date") java.time.LocalDate date);

    /** The most recently loaded cycle, which is what a service falls back to. */
    Optional<NavigationEntities.CycleEntity> findFirstByOrderByEffectiveDateDesc();
}
