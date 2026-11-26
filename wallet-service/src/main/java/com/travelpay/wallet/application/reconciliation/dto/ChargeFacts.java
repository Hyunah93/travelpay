package com.travelpay.wallet.application.reconciliation.dto;

import java.util.Objects;

/**
 * 한 충전에 대해 확인한 은행 출금·은행 환불·원장 적립(CHARGE)·원장 취소 분개(CHARGE_REVERSAL) 사실.
 * 취소 분개는 대사 기간 목록에서 찾고, 이중 지급 사실이 있는데 목록에 없을 때만 단건 조회한다 (실패하면 Unavailable)
 */
public record ChargeFacts(SourceLookup<BankTransactionRecord> debit,
                          SourceLookup<BankTransactionRecord> refund,
                          SourceLookup<LedgerTransactionRecord> ledger,
                          SourceLookup<LedgerTransactionRecord> reversal) {

    public ChargeFacts {
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(reversal, "reversal");
    }

    public boolean hasReversal() {
        return reversal instanceof SourceLookup.Found<LedgerTransactionRecord>;
    }
}
