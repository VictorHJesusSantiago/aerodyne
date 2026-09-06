package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Navaids. */
public interface NavaidRepository extends JpaRepository<NavigationEntities.NavaidEntity, Long> {

    List<NavigationEntities.NavaidEntity> findByCycleId(Long cycleId);

    List<NavigationEntities.NavaidEntity> findByCycleIdAndIdent(Long cycleId, String ident);

    @Query(value = """
            select * from navaid n
            where n.cycle_id = :cycleId
              and ST_DWithin(n.position, ST_MakePoint(:longitude, :latitude)::geography, :radiusMetres)
            order by ST_Distance(n.position, ST_MakePoint(:longitude, :latitude)::geography)
            limit :limit
            """, nativeQuery = true)
    List<NavigationEntities.NavaidEntity> findNear(@Param("cycleId") Long cycleId,
                                                   @Param("latitude") double latitude,
                                                   @Param("longitude") double longitude,
                                                   @Param("radiusMetres") double radiusMetres,
                                                   @Param("limit") int limit);
}
