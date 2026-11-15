package com.travelpay.ledger.domain.integrity;

/**
 * 원장 내부 정합성 검사. 찾기만 하고 고치지 않는다.
 * 검사마다 SQL 한 문장이라 한 검사 안에서는 같은 시점을 읽지만, 검사끼리는 시점이 다를 수 있다.
 * violationLimit: 위반 목록으로 돌려줄 최대 건수 (정렬 후 앞쪽부터)
 */
public interface LedgerIntegrityRepository {

    /** 검사 동시 실행을 막는 트랜잭션 범위 잠금을 기다리지 않고 시도한다. 잡았으면 true, 트랜잭션이 끝나면 풀린다 */
    boolean tryLockForCheck();

    /** 1. 계정 잔액 = 그 계정 POSTED 거래 항목의 (CREDIT 합 - DEBIT 합). 검사 대상은 모든 계정 */
    IntegrityCheck<AccountBalanceViolation> checkAccountBalances(int violationLimit);

    /** 2. POSTED 거래마다 통화별 DEBIT 합 = CREDIT 합. 검사 대상은 POSTED 거래, 위반 단위는 (거래, 통화) */
    IntegrityCheck<TransactionBalanceViolation> checkPostedTransactionBalances(int violationLimit);

    /** 3. REJECTED 거래에 항목이 없음. 검사 대상은 REJECTED 거래 */
    IntegrityCheck<RejectedEntriesViolation> checkRejectedTransactionsHaveNoEntries(int violationLimit);

    /** 4. 통화별 전체 계정 잔액 합 = 0. 검사 대상은 모든 계정, 위반 단위는 통화 */
    IntegrityCheck<CurrencyTotalViolation> checkCurrencyTotals(int violationLimit);
}
