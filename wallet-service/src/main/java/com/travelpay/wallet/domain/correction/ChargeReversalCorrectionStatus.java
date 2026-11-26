package com.travelpay.wallet.domain.correction;

/** 이중 지급 정정 상태 */
public enum ChargeReversalCorrectionStatus {
    /** 정정 대기 (next_attempt_at 이후 스케줄러가 처리) */
    PENDING,
    /** 원장 충전 취소 분개 기록됨 (ledger_tx_id·회수·미수 금액 있음) */
    SUCCEEDED,
    /** 자동으로 끝낼 수 없어 사람이 확인해야 함 */
    NEEDS_REVIEW
}
