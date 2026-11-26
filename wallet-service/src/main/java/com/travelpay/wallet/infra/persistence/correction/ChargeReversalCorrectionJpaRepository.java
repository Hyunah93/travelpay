package com.travelpay.wallet.infra.persistence.correction;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ChargeReversalCorrectionJpaRepository extends JpaRepository<ChargeReversalCorrection, UUID> {

    /**
     * flushAutomatically: 실행 전에 EntityManager.flush()로 같은 트랜잭션에서 saveAll한 대사 결과 INSERT를 먼저 보낸다
     * (reconciliation_result_id FK가 그 행을 찾을 수 있도록). clearAutomatically: 실행 후 영속성 컨텍스트를 비운다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into charge_reversal_corrections
                (id, charge_id, reconciliation_result_id, status, attempt_count, next_attempt_at, executed_by,
                 created_at, updated_at)
            values (:id, :chargeId, :resultId, 'PENDING', 0, :now, 'SYSTEM', :now, :now)
            on conflict (charge_id) do nothing
            """)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("chargeId") UUID chargeId,
                       @Param("resultId") UUID resultId,
                       @Param("now") Instant now);
}
