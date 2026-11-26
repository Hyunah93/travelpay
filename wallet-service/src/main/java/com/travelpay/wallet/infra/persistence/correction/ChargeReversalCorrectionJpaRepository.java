package com.travelpay.wallet.infra.persistence.correction;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrection;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 시각(updated_at·next_attempt_at)은 복구 선점(claimForRecovery)처럼 DB now()로 정한다.
 * 결과 기록 UPDATE는 모두 status = 'PENDING' and updated_at = :token 조건이다 (선점 토큰, 서비스 주석 참고)
 */
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

    /** ix_charge_reversal_corrections_pending (next_attempt_at) WHERE status = 'PENDING' 범위 스캔 */
    @Query(nativeQuery = true, value = """
            select id, charge_id as "chargeId", attempt_count as "attemptCount", updated_at as "updatedAt"
              from charge_reversal_corrections
             where status = 'PENDING'
               and next_attempt_at <= now()
             order by next_attempt_at, id
             limit :limit
            """)
    List<ChargeReversalCorrectionRow> findDueTargets(@Param("limit") int limit);

    /**
     * 선점. RETURNING으로 바뀐 updated_at을 같은 문장에서 받는다 (0행이면 빈 목록).
     * 행을 돌려주는 쿼리라 @Modifying 없이 결과 목록으로 실행하며, 쓰기 트랜잭션 안에서만 호출한다
     */
    @Query(nativeQuery = true, value = """
            update charge_reversal_corrections
               set updated_at = now(),
                   next_attempt_at = now() + :leaseSeconds * interval '1 second'
             where id = :id
               and status = 'PENDING'
               and updated_at = :updatedAt
            returning updated_at as "updatedAt"
            """)
    List<ChargeReversalClaimRow> claim(@Param("id") UUID id,
                                       @Param("updatedAt") Instant updatedAt,
                                       @Param("leaseSeconds") long leaseSeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update charge_reversal_corrections
               set status = 'SUCCEEDED',
                   ledger_tx_id = :ledgerTxId,
                   recovered_amount = :recovered,
                   receivable_amount = :receivable,
                   last_error = null,
                   updated_at = now()
             where id = :id
               and status = 'PENDING'
               and updated_at = :token
            """)
    int recordSucceeded(@Param("id") UUID id,
                        @Param("token") Instant token,
                        @Param("ledgerTxId") UUID ledgerTxId,
                        @Param("recovered") long recovered,
                        @Param("receivable") long receivable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update charge_reversal_corrections
               set status = 'NEEDS_REVIEW',
                   attempt_count = :attemptCount,
                   last_error = :lastError,
                   updated_at = now()
             where id = :id
               and status = 'PENDING'
               and updated_at = :token
            """)
    int recordNeedsReview(@Param("id") UUID id,
                          @Param("token") Instant token,
                          @Param("attemptCount") int attemptCount,
                          @Param("lastError") String lastError);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update charge_reversal_corrections
               set attempt_count = :attemptCount,
                   last_error = :lastError,
                   next_attempt_at = now() + :delaySeconds * interval '1 second',
                   updated_at = now()
             where id = :id
               and status = 'PENDING'
               and updated_at = :token
            """)
    int recordRetry(@Param("id") UUID id,
                    @Param("token") Instant token,
                    @Param("attemptCount") int attemptCount,
                    @Param("delaySeconds") long delaySeconds,
                    @Param("lastError") String lastError);

    long countByStatus(ChargeReversalCorrectionStatus status);

    Page<ChargeReversalCorrection> findByStatus(ChargeReversalCorrectionStatus status, Pageable pageable);
}
