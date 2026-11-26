package com.travelpay.wallet.domain.correction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 이중 지급 정정 대상 충전 1건 (충전당 1행, 실행 주체 SYSTEM).
 * 행은 대사가 조건부 INSERT(ON CONFLICT DO NOTHING)로만 만들고, 이 엔티티로 저장하지 않는다
 */
@Getter
@Entity
@Table(name = "charge_reversal_corrections")
public class ChargeReversalCorrection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Column(name = "reconciliation_result_id", nullable = false, updatable = false)
    private UUID reconciliationResultId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChargeReversalCorrectionStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "ledger_tx_id")
    private UUID ledgerTxId;

    @Column(name = "recovered_amount")
    private Long recoveredAmount;

    @Column(name = "receivable_amount")
    private Long receivableAmount;

    @Column(name = "executed_by", nullable = false, updatable = false, length = 10)
    private String executedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChargeReversalCorrection() {
    }
}
