package com.travelpay.wallet.domain.correction;

import java.time.Instant;
import java.util.UUID;

/**
 * 정정 처리 대상. updatedAt은 DB에서 읽은 값 그대로이며 선점 조건(updated_at = ?)에 쓴다.
 * attemptCount는 선점 후 결과 기록까지 선점 토큰 조건으로 다른 처리기가 바꾸지 못하므로 결과 모름 횟수 계산에 그대로 쓴다
 */
public record ChargeReversalCorrectionTarget(UUID id, UUID chargeId, int attemptCount, Instant updatedAt) {
}
