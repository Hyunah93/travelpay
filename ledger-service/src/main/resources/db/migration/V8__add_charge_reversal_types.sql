-- 충전 취소 분개(이중 지급 바로잡기)용 참조 유형·거래 항목 유형·계정 유형 추가 (이슈 #1)
-- 취소 분개 한 거래: 회원 지갑 DEBIT CHARGE_REVERSAL_OUT(잔액으로 회수한 금액)
--                  + 미수 RECEIVABLE DEBIT CHARGE_REVERSAL_RECEIVABLE(부족분)
--                  + 충전 원천 CREDIT CHARGE_REVERSAL_IN(원금액)
-- 이 값들은 충전 취소 분개 API만 기록한다. 거래 등록·취소 API는 400으로 거절한다

-- reference_type: 기존 값에 CHARGE_REVERSAL 추가
-- 기존 제약은 검증된 상태이고 새 목록은 기존 값을 모두 포함하므로 바로 검증해도 기존 행이 통과한다
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_reference_type;
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reference_type
        CHECK (reference_type IN ('CHARGE', 'PAYMENT', 'PAYMENT_REFUND', 'TRANSFER', 'EXCHANGE', 'SETTLEMENT',
                                  'CHARGE_REVERSAL'));

-- entry_type: 기존 13개에 CHARGE_REVERSAL_OUT, CHARGE_REVERSAL_IN, CHARGE_REVERSAL_RECEIVABLE 추가
-- 팀원 로컬 DB 확인 전이라 기존과 같이 NOT VALID 유지 (새로 들어오는 행만 검사)
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_entry_type
        CHECK (entry_type IN (
                              'CHARGE_OUT', 'CHARGE_IN',
                              'PAYMENT_OUT', 'PAYMENT_IN',
                              'PAYMENT_REFUND_OUT', 'PAYMENT_REFUND_IN',
                              'EXCHANGE_OUT', 'EXCHANGE_IN', 'EXCHANGE_FEE',
                              'TRANSFER_OUT', 'TRANSFER_IN',
                              'SETTLEMENT_OUT', 'SETTLEMENT_IN',
                              'CHARGE_REVERSAL_OUT', 'CHARGE_REVERSAL_IN', 'CHARGE_REVERSAL_RECEIVABLE'
            )) NOT VALID;

-- account_type: 기존 값에 RECEIVABLE(미수) 추가
-- 원장 계정은 종류와 상관없이 CREDIT 증가·DEBIT 감소이고 USER는 음수 금지라 미수는 SYSTEM 계정에 둔다
-- 기존 제약은 검증된 상태이고 새 목록은 기존 값을 모두 포함하므로 바로 검증해도 기존 행이 통과한다
ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_accounts_account_type;
ALTER TABLE ledger_accounts
    ADD CONSTRAINT ck_ledger_accounts_account_type
        CHECK (account_type IN ('WALLET', 'CHARGE_SOURCE', 'FX_POSITION', 'FEE_REVENUE', 'MERCHANT_SETTLEMENT',
                                'RECEIVABLE'));

-- 미수 시스템 계정 (충전 취소 분개에서 회수하지 못한 부족분의 DEBIT 쪽). 충전은 KRW만 쓴다
INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type, balance, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'KRW', 'RECEIVABLE', 0, 'ACTIVE', now(), now())
    ON CONFLICT (account_type, currency) WHERE owner_type = 'SYSTEM' DO NOTHING;
