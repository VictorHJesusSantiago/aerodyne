package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Waypoints. */
public interface WaypointRepository extends JpaRepository<NavigationEntities.WaypointEntity, Long> {

    List<NavigationEntities.WaypointEntity> findByCycleId(Long cycleId);

    List<NavigationEntities.WaypointEntity> findByCycleIdAndIdent(Long cycleId, String ident);

    @Query(value = """
            select * from waypoint w
            where w.cycle_id = :cycleId
              and ST_DWithin(w.position, ST_MakePoint(:longitude, :latitude)::geography, :radiusMetres)
            order by ST_Distance(w.position, ST_MakePoint(:longitude, :latitude)::geography)
            limit :limit
            """, nativeQuery = true)
    List<NavigationEntities.WaypointEntity> findNear(@Param("cycleId") Long cycleId,
                                                     @Param("latitude") double latitude,
                                                     @Param("longitude") double longitude,
                                                     @Param("radiusMetres") double radiusMetres,
                                                     @Param("limit") int limit);
}
