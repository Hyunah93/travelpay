-- wallet-service: src/main/resources/db/migration/V7__create_charge_reversal_corrections.sql
-- 스키마: wallet_service
-- 이중 지급 정정: 대사가 남긴 CREDIT_AND_REFUND_BOTH 충전을 시스템이 원장 충전 취소 분개로 바로잡는 대상과 결과
-- 충전당 1행. 대사 결과(reconciliation_results.resolution_status)와 운영자 보정(charge_resolutions)은 건드리지 않는다
-- last_error에는 사유 코드·짧은 설명만 남긴다 (응답 원문·계좌 정보 금지)

-- =========================================================
-- charge_reversal_corrections: 정정 대상 충전 1건 = 1행
-- =========================================================
CREATE TABLE charge_reversal_corrections (
    id                        UUID          NOT NULL,
    charge_id                 UUID          NOT NULL,
    -- 처음 발견한 대사 결과 (같은 실행에서 저장한 그 충전의 CREDIT_AND_REFUND_BOTH 행)
    reconciliation_result_id  UUID          NOT NULL,
    status                    VARCHAR(20)   NOT NULL,
    attempt_count             INT           NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMPTZ   NOT NULL,
    last_error                VARCHAR(500)  NULL,
    -- 원장 취소 분개 결과 (SUCCEEDED일 때만, 셋 다 함께)
    ledger_tx_id              UUID          NULL,
    recovered_amount          BIGINT        NULL,
    receivable_amount         BIGINT        NULL,
    executed_by               VARCHAR(10)   NOT NULL DEFAULT 'SYSTEM',
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_charge_reversal_corrections PRIMARY KEY (id),
    CONSTRAINT uk_charge_reversal_corrections_charge UNIQUE (charge_id),
    CONSTRAINT fk_charge_reversal_corrections_charge
        FOREIGN KEY (charge_id) REFERENCES charge_requests (id),
    CONSTRAINT fk_charge_reversal_corrections_reconciliation_result
        FOREIGN KEY (reconciliation_result_id) REFERENCES reconciliation_results (id),
    CONSTRAINT ck_charge_reversal_corrections_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'NEEDS_REVIEW')),
    CONSTRAINT ck_charge_reversal_corrections_executed_by CHECK (executed_by = 'SYSTEM'),
    CONSTRAINT ck_charge_reversal_corrections_attempt_count CHECK (attempt_count >= 0),
    -- 결과 칸은 상태별로 셋 다 채우거나 셋 다 비운다 (일부만 채운 행 금지)
    CONSTRAINT ck_charge_reversal_corrections_result CHECK (
        (status = 'SUCCEEDED'
            AND ledger_tx_id IS NOT NULL AND recovered_amount IS NOT NULL AND receivable_amount IS NOT NULL)
        OR (status IN ('PENDING', 'NEEDS_REVIEW')
            AND ledger_tx_id IS NULL AND recovered_amount IS NULL AND receivable_amount IS NULL)),
    CONSTRAINT ck_charge_reversal_corrections_amounts CHECK (recovered_amount >= 0 AND receivable_amount >= 0)
);

-- 스케줄러 대상 조회: PENDING 중 next_attempt_at이 지난 건
CREATE INDEX ix_charge_reversal_corrections_pending
    ON charge_reversal_corrections (next_attempt_at) WHERE status = 'PENDING';
