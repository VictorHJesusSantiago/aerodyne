package io.aerodyne.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Terminal procedures, by cycle and aerodrome. */
public interface ProcedureRepository extends JpaRepository<ProcedureEntity, Long> {

    List<ProcedureEntity> findByCycleId(Long cycleId);

    List<ProcedureEntity> findByCycleIdAndAirportIcao(Long cycleId, String airportIcao);

    Optional<ProcedureEntity> findByCycleIdAndAirportIcaoAndIdent(Long cycleId, String airportIcao,
                                                                  String ident);
}
