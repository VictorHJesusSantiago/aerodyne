package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Airways, with their points. */
public interface AirwayRepository extends JpaRepository<NavigationEntities.AirwayEntity, Long> {

    List<NavigationEntities.AirwayEntity> findByCycleId(Long cycleId);

    Optional<NavigationEntities.AirwayEntity> findByCycleIdAndIdent(Long cycleId, String ident);
}
