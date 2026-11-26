package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.transaction.EntryType;

/**
 * 충전 취소 분개 결과. 금액은 저장된 항목에서 읽으므로 새로 기록한 경우와 이미 있던 경우가 같은 규칙이다.
 * recoveredAmount: 지갑에서 회수한 금액(CHARGE_REVERSAL_OUT), receivableAmount: 미수 금액(CHARGE_REVERSAL_RECEIVABLE).
 * created: 이번 요청으로 새로 기록했는지 (false면 이미 있던 취소 분개를 그대로 돌려줌)
 */
public record ChargeReversalResult(TransactionDetailResult transaction, long recoveredAmount, long receivableAmount,
                                   boolean created) {

    public static ChargeReversalResult of(TransactionDetailResult transaction, boolean created) {
        return new ChargeReversalResult(transaction,
                sum(transaction, EntryType.CHARGE_REVERSAL_OUT),
                sum(transaction, EntryType.CHARGE_REVERSAL_RECEIVABLE),
                created);
    }

    private static long sum(TransactionDetailResult transaction, EntryType entryType) {
        return transaction.entries().stream()
                .filter(entry -> entryType.name().equals(entry.entryType()))
                .mapToLong(LedgerEntryResult::amount)
                .reduce(0L, Math::addExact);
    }
}
