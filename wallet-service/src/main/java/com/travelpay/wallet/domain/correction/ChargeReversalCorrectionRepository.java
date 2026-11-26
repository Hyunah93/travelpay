package com.travelpay.wallet.domain.correction;

import java.time.Instant;
import java.util.UUID;

public interface ChargeReversalCorrectionRepository {

    /**
     * 충전의 정정 대상이 없을 때만 PENDING(attempt 0, next_attempt_at = now)으로 등록한다.
     * 이미 있으면 상태와 관계없이 바꾸지 않는다 (ON CONFLICT (charge_id) DO NOTHING). 등록한 행 수(0 또는 1).
     * reconciliationResultId는 FK라, 실행 전에 영속성 컨텍스트의 대사 결과 INSERT를 먼저 DB에 반영한다.
     * 트랜잭션 안에서만 사용
     */
    int registerIfAbsent(UUID chargeId, UUID reconciliationResultId, Instant now);
}
