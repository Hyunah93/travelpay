package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 판정 기준 중 대사가 쓰지 않는 금액 일치 조건. 나머지 기준은 대사 통합 테스트가 확인한다 */
class DoublePaymentCriteriaTest {

    private static final long AMOUNT = 10_000;

    private final ChargeRequest charge = mock(ChargeRequest.class);
    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(charge.getId()).thenReturn(UUID.randomUUID());
        when(charge.getWalletId()).thenReturn(walletId);
        when(charge.getAmount()).thenReturn(AMOUNT);
        when(charge.getCurrency()).thenReturn(Currency.KRW);
    }

    @Test
    void 환불_성공과_원장_적립의_금액_통화가_충전과_같으면_이중_지급() {
        assertTrue(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                ledger("POSTED", walletId, AMOUNT, "KRW")));
    }

    @Test
    void 환불이나_원장_적립이_없거나_성공이_아니면_아님() {
        SourceLookup<LedgerTransactionRecord> posted = ledger("POSTED", walletId, AMOUNT, "KRW");
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, SourceLookup.notFound(), posted));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("FAILED", AMOUNT), posted));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                ledger("REJECTED", walletId, AMOUNT, "KRW")));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                SourceLookup.unavailable("timeout")));
    }

    @Test
    void 금액_통화_지갑이_하나라도_다르면_아님() {
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT - 1),
                ledger("POSTED", walletId, AMOUNT, "KRW")));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                ledger("POSTED", walletId, AMOUNT + 1, "KRW")));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                ledger("POSTED", walletId, AMOUNT, "JPY")));
        assertFalse(DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund("SUCCEEDED", AMOUNT),
                ledger("POSTED", UUID.randomUUID(), AMOUNT, "KRW")));
    }

    @Test
    void 금액을_보지_않는_isDoublePaid는_금액이_달라도_이중_지급() {
        assertTrue(DoublePaymentCriteria.isDoublePaid(refund("SUCCEEDED", AMOUNT - 1),
                ledger("POSTED", walletId, AMOUNT + 1, "JPY")));
    }

    private static SourceLookup<BankTransactionRecord> refund(String status, long amount) {
        return SourceLookup.found(new BankTransactionRecord("r", UUID.randomUUID(), "REFUND", status, amount));
    }

    private static SourceLookup<LedgerTransactionRecord> ledger(String status, UUID walletId, long amount,
                                                                String currency) {
        return SourceLookup.found(new LedgerTransactionRecord(UUID.randomUUID(), status, "CHARGE", UUID.randomUUID(),
                List.of(new LedgerEntryRecord("SYSTEM", null, "CHARGE_SOURCE", "DEBIT", currency, amount, "CHARGE_OUT"),
                        new LedgerEntryRecord("USER", walletId, "WALLET", "CREDIT", currency, amount, "CHARGE_IN"))));
    }
}
