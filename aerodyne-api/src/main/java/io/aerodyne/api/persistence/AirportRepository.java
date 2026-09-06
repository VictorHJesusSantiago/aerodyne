package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Airports, with their runways. */
public interface AirportRepository extends JpaRepository<NavigationEntities.AirportEntity, Long> {

    List<NavigationEntities.AirportEntity> findByCycleId(Long cycleId);

    Optional<NavigationEntities.AirportEntity> findByCycleIdAndIcaoCode(Long cycleId, String icaoCode);

    /**
     * Airports within a radius of a position, nearest first. The radius is in metres,
     * because that is what {@code geography} measures in.
     */
    @Query(value = """
            select * from airport a
            where a.cycle_id = :cycleId
              and ST_DWithin(a.position, ST_MakePoint(:longitude, :latitude)::geography, :radiusMetres)
            order by ST_Distance(a.position, ST_MakePoint(:longitude, :latitude)::geography)
            limit :limit
            """, nativeQuery = true)
    List<NavigationEntities.AirportEntity> findNear(@Param("cycleId") Long cycleId,
                                                    @Param("latitude") double latitude,
                                                    @Param("longitude") double longitude,
                                                    @Param("radiusMetres") double radiusMetres,
                                                    @Param("limit") int limit);
}
