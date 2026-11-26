package com.travelpay.ledger.domain.integrity;

import java.util.UUID;

/** 충전 취소 분개 구조가 어긋난 거래와 이유. 한 거래가 여러 이유로 걸리면 이유마다 한 건 */
public record ChargeReversalViolation(UUID transactionId, Reason reason) {

    public enum Reason {
        /** original_transaction_id가 POSTED CHARGE 거래가 아니거나 그 reference_id가 chargeId와 다름 */
        ORIGINAL_MISMATCH,
        /** 충전 원천 CREDIT CHARGE_REVERSAL_IN 금액·통화가 원거래 지갑 적립(CHARGE_IN)과 다름 */
        REVERSAL_IN_MISMATCH,
        /** 지갑 회수(CHARGE_REVERSAL_OUT) + 미수(CHARGE_REVERSAL_RECEIVABLE) ≠ CHARGE_REVERSAL_IN 금액 */
        SPLIT_MISMATCH,
        /** 지갑 회수 항목이 원거래 CHARGE_IN과 다른 지갑 계정 */
        WALLET_MISMATCH,
        /** 미수(RECEIVABLE) 계정 항목이 CHARGE_REVERSAL이 아닌 거래에 있음 */
        RECEIVABLE_OUTSIDE_REVERSAL
    }
}
