-- wallet-service: src/main/resources/db/migration/V6__add_reversal_mismatch_type.sql
-- 스키마: wallet_service
-- 대사 결과 유형에 REVERSAL_MISMATCH(충전 취소 분개 구조 불일치·이중 지급 아닌 충전의 취소 분개) 추가

ALTER TABLE reconciliation_results DROP CONSTRAINT ck_reconciliation_results_mismatch_type;
ALTER TABLE reconciliation_results
    ADD CONSTRAINT ck_reconciliation_results_mismatch_type CHECK (mismatch_type IN (
        'BANK_DEBIT_WITHOUT_CREDIT', 'CREDIT_WITHOUT_BANK_DEBIT', 'CREDIT_AND_REFUND_BOTH', 'AMOUNT_MISMATCH',
        'STATUS_MISMATCH', 'BANK_ORPHAN', 'LEDGER_ORPHAN', 'REVERSAL_MISMATCH', 'REVIEW_REQUIRED',
        'SOURCE_UNAVAILABLE'));
