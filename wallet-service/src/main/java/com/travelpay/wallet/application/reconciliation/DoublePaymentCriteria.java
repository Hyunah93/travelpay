package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 이중 지급과 충전 취소 분개 판정 기준 (외부 호출·DB 없음). 대사(ChargeReconciliationJudge·ChargeReconciliationService)와
 * 이중 지급 정정이 같은 기준을 쓰도록 모은다.
 * <p>
 * 대사는 {@link #isDoublePaid}(금액 비교 없음)를 쓰고 금액 차이는 AMOUNT_MISMATCH로 따로 남긴다.
 * {@link #isDoublePaidWithMatchingAmounts}는 금액까지 맞는 이중 지급만 인정한다.
 */
public final class DoublePaymentCriteria {

    private static final String CHARGE_REVERSAL = "CHARGE_REVERSAL";
    private static final String POSTED = "POSTED";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";
    private static final String WALLET = "WALLET";
    private static final String RECEIVABLE = "RECEIVABLE";
    private static final String CHARGE_SOURCE = "CHARGE_SOURCE";
    private static final String REVERSAL_OUT = "CHARGE_REVERSAL_OUT";
    private static final String REVERSAL_IN = "CHARGE_REVERSAL_IN";
    private static final String REVERSAL_RECEIVABLE = "CHARGE_REVERSAL_RECEIVABLE";

    private DoublePaymentCriteria() {
    }

    public static boolean isSucceeded(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found && found.record().isSucceeded();
    }

    public static boolean isPosted(SourceLookup<LedgerTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<LedgerTransactionRecord> found && found.record().isPosted();
    }

    /** 이중 지급 사실: 은행 환불 SUCCEEDED + 원장 적립(CHARGE) POSTED */
    public static boolean isDoublePaid(SourceLookup<BankTransactionRecord> refund,
                                       SourceLookup<LedgerTransactionRecord> ledger) {
        return isSucceeded(refund) && isPosted(ledger);
    }

    /**
     * isDoublePaid + 금액 일치: 환불 금액 = 충전 금액, 원장의 충전 지갑 적립 항목(USER·CREDIT·CHARGE_IN)의
     * 금액·통화 = 충전 금액·통화
     */
    public static boolean isDoublePaidWithMatchingAmounts(ChargeRequest charge,
                                                          SourceLookup<BankTransactionRecord> refund,
                                                          SourceLookup<LedgerTransactionRecord> ledger) {
        if (!isDoublePaid(refund, ledger)) {
            return false;
        }
        BankTransactionRecord refundRecord = ((SourceLookup.Found<BankTransactionRecord>) refund).record();
        LedgerTransactionRecord ledgerRecord = ((SourceLookup.Found<LedgerTransactionRecord>) ledger).record();
        if (refundRecord.amount() != charge.getAmount()) {
            return false;
        }
        Optional<LedgerEntryRecord> entry = ledgerRecord.chargeInEntry(charge.getWalletId());
        return entry.isPresent() && entry.get().amount() == charge.getAmount()
                && charge.getCurrency().name().equals(entry.get().currency());
    }

    /**
     * 정상 취소 분개: POSTED, referenceType CHARGE_REVERSAL, referenceId = chargeId,
     * CHARGE_SOURCE CREDIT CHARGE_REVERSAL_IN 1항목의 금액·통화 = 충전 금액·통화,
     * 지갑 DEBIT CHARGE_REVERSAL_OUT 합 + RECEIVABLE DEBIT CHARGE_REVERSAL_RECEIVABLE 합 = CHARGE_REVERSAL_IN 금액,
     * CHARGE_REVERSAL_OUT은 충전 지갑, 그 밖의 항목 없음. 어긋난 이유 목록 (정상이면 빈 목록)
     */
    public static List<String> reversalProblems(ChargeRequest charge, LedgerTransactionRecord reversal) {
        List<String> problems = new ArrayList<>();
        if (!POSTED.equals(reversal.status())) {
            problems.add("취소 분개 상태 " + reversal.status() + " (POSTED여야 함)");
        }
        if (!CHARGE_REVERSAL.equals(reversal.referenceType())) {
            problems.add("취소 분개 referenceType " + reversal.referenceType());
        }
        if (!charge.getId().equals(reversal.referenceId())) {
            problems.add("취소 분개 referenceId " + reversal.referenceId() + " ≠ 충전 id");
        }
        String currency = charge.getCurrency().name();
        List<LedgerEntryRecord> in = new ArrayList<>();
        long split = 0;
        for (LedgerEntryRecord e : reversal.entries()) {
            boolean isIn = REVERSAL_IN.equals(e.entryType()) && CHARGE_SOURCE.equals(e.accountType())
                    && CREDIT.equals(e.direction());
            boolean isOut = REVERSAL_OUT.equals(e.entryType()) && WALLET.equals(e.accountType())
                    && DEBIT.equals(e.direction());
            boolean isReceivable = REVERSAL_RECEIVABLE.equals(e.entryType()) && RECEIVABLE.equals(e.accountType())
                    && DEBIT.equals(e.direction());
            if (!isIn && !isOut && !isReceivable) {
                problems.add("취소 분개에 허용되지 않은 항목 (" + e.accountType() + " " + e.direction() + " "
                        + e.entryType() + ")");
                continue;
            }
            if (!currency.equals(e.currency())) {
                problems.add("취소 분개 " + e.entryType() + " 통화(" + e.currency() + ") ≠ 충전 통화(" + currency + ")");
            }
            if (isIn) {
                in.add(e);
            } else {
                split += e.amount();
            }
            if (isOut && !charge.getWalletId().equals(e.walletId())) {
                problems.add("취소 분개 CHARGE_REVERSAL_OUT 지갑(" + e.walletId() + ") ≠ 충전 지갑("
                        + charge.getWalletId() + ")");
            }
        }
        if (in.size() != 1) {
            problems.add("취소 분개 CHARGE_REVERSAL_IN 항목 " + in.size() + "개 (1개여야 함)");
            return problems;
        }
        long inAmount = in.getFirst().amount();
        if (inAmount != charge.getAmount()) {
            problems.add("취소 분개 CHARGE_REVERSAL_IN 금액(" + inAmount + ") ≠ 충전 금액(" + charge.getAmount() + ")");
        }
        if (split != inAmount) {
            problems.add("취소 분개 회수+미수(" + split + ") ≠ CHARGE_REVERSAL_IN 금액(" + inAmount + ")");
        }
        return problems;
    }
}
