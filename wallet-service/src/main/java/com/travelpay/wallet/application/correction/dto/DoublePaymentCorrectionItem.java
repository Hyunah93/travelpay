package com.travelpay.wallet.application.correction.dto;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrection;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;

import java.time.Instant;
import java.util.UUID;

/** 관리자 조회용 이중 지급 정정 1건. 금액·ledgerTxId는 SUCCEEDED일 때만 있다 */
public record DoublePaymentCorrectionItem(UUID id, UUID chargeId, UUID reconciliationResultId,
                                          ChargeReversalCorrectionStatus status, int attemptCount,
                                          Instant nextAttemptAt, String lastError, UUID ledgerTxId,
                                          Long recoveredAmount, Long receivableAmount, Instant createdAt,
                                          Instant updatedAt) {

    public static DoublePaymentCorrectionItem from(ChargeReversalCorrection c) {
        return new DoublePaymentCorrectionItem(c.getId(), c.getChargeId(), c.getReconciliationResultId(),
                c.getStatus(), c.getAttemptCount(), c.getNextAttemptAt(), c.getLastError(), c.getLedgerTxId(),
                c.getRecoveredAmount(), c.getReceivableAmount(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
