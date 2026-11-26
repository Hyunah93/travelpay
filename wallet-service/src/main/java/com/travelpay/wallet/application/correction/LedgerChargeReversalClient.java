package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.application.correction.dto.ChargeReversalResult;

import java.util.UUID;

/**
 * 원장 충전 취소 분개 요청 (POST /internal/v1/ledger/transactions/charge-reversals). chargeId 기준 멱등이라
 * 같은 chargeId 재요청은 처음 기록을 돌려받는다. 호출 실패도 예외가 아니라 ChargeReversalResult로 알린다.
 * chargeAmount는 요청에 싣지 않고 응답 금액 검사에만 쓴다
 */
public interface LedgerChargeReversalClient {

    ChargeReversalResult reverse(UUID chargeId, long chargeAmount, String description);
}
