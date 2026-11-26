package com.travelpay.wallet.domain.correction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 정정 대상 저장소. 상태와 결과 칸은 조건부 UPDATE로만 바꾸고 updated_at도 쿼리에서 DB now()로 직접 갱신한다
 * (벌크 UPDATE라 엔티티 콜백이 돌지 않는다). 쓰기 메서드는 트랜잭션 안에서만 사용
 */
public interface ChargeReversalCorrectionRepository {

    /**
     * 충전의 정정 대상이 없을 때만 PENDING(attempt 0, next_attempt_at = now)으로 등록한다.
     * 이미 있으면 상태와 관계없이 바꾸지 않는다 (ON CONFLICT (charge_id) DO NOTHING). 등록한 행 수(0 또는 1).
     * reconciliationResultId는 FK라, 실행 전에 영속성 컨텍스트의 대사 결과 INSERT를 먼저 DB에 반영한다
     */
    int registerIfAbsent(UUID chargeId, UUID reconciliationResultId, Instant now);

    /** PENDING이고 next_attempt_at ≤ DB now()인 건을 next_attempt_at 순으로 limit건 */
    List<ChargeReversalCorrectionTarget> findDueTargets(int limit);

    /**
     * 선점: PENDING이고 updated_at이 읽은 값과 같을 때만 updated_at = now(), next_attempt_at = now() + lease.
     * 바뀐 updated_at(선점 토큰)을 돌려주고, 다른 처리기가 먼저 바꿨으면 empty
     */
    Optional<Instant> claim(UUID id, Instant updatedAt, Duration lease);

    /** 결과 기록(SUCCEEDED). PENDING이고 updated_at = token일 때만. 바뀐 행 수 */
    int recordSucceeded(UUID id, Instant token, UUID ledgerTxId, long recoveredAmount, long receivableAmount);

    /** 결과 기록(NEEDS_REVIEW). attemptCount·lastError를 함께 쓴다. PENDING이고 updated_at = token일 때만 */
    int recordNeedsReview(UUID id, Instant token, int attemptCount, String lastError);

    /** 결과 모름 기록: PENDING 유지, attemptCount·lastError, next_attempt_at = now() + delay. 조건은 위와 같다 */
    int recordRetry(UUID id, Instant token, int attemptCount, Duration delay, String lastError);

    long countByStatus(ChargeReversalCorrectionStatus status);

    /** status가 null이면 전체 */
    Page<ChargeReversalCorrection> findAll(ChargeReversalCorrectionStatus status, Pageable pageable);
}
