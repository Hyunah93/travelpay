package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.application.reconciliation.DoublePaymentCriteria;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 정정 1건 판정 (외부 호출·DB 없음). 기준은 대사와 같은 DoublePaymentCriteria이고, 원장에 보낼지는
 * 금액까지 맞는 이중 지급(isDoublePaidWithMatchingAmounts)만 인정한다.
 * <ol>
 *   <li>재조회 중 하나라도 Unavailable → Unavailable (아무것도 기록하지 않음)</li>
 *   <li>취소 분개 Found: 구조 정상이면 AlreadyReversed(항목에서 회수·미수 계산), 이상이면 REVERSAL_MALFORMED</li>
 *   <li>취소 분개 NotFound: 이중 지급이면 Post, 아니면 기준 순서로 처음 틀린 것 하나(NO_REFUND → NO_CREDIT → AMOUNT_MISMATCH)</li>
 * </ol>
 */
public final class DoublePaymentCorrectionJudge {

    /** 정정 대상 충전 상태. 이중 지급 사실(환불 + 적립)이 나올 수 있는 종료 상태 */
    static final Set<ChargeStatus> CORRECTABLE_STATUSES = Set.of(ChargeStatus.COMPLETED, ChargeStatus.REFUNDED);

    private static final String REVERSAL_OUT = "CHARGE_REVERSAL_OUT";
    private static final String REVERSAL_RECEIVABLE = "CHARGE_REVERSAL_RECEIVABLE";

    private DoublePaymentCorrectionJudge() {
    }

    public sealed interface Decision {
    }

    /** 재조회 실패. reason은 실패한 소스 이름 */
    public record Unavailable(String reason) implements Decision {
    }

    /** 정상 취소 분개가 이미 있음 */
    public record AlreadyReversed(UUID ledgerTxId, long recoveredAmount, long receivableAmount) implements Decision {
    }

    public record NeedsReview(CorrectionErrorCode code, String detail) implements Decision {
    }

    /** 원장에 충전 취소 분개를 요청한다 */
    public record Post() implements Decision {
    }

    public static boolean isCorrectable(ChargeStatus status) {
        return CORRECTABLE_STATUSES.contains(status);
    }

    public static Decision decide(ChargeRequest charge, SourceLookup<BankTransactionRecord> refund,
                                  SourceLookup<LedgerTransactionRecord> ledger,
                                  SourceLookup<LedgerTransactionRecord> reversal) {
        String unavailable = unavailableSources(refund, ledger, reversal);
        if (!unavailable.isEmpty()) {
            return new Unavailable(unavailable);
        }
        if (reversal instanceof SourceLookup.Found<LedgerTransactionRecord> found) {
            return existingReversal(charge, found.record());
        }
        if (DoublePaymentCriteria.isDoublePaidWithMatchingAmounts(charge, refund, ledger)) {
            return new Post();
        }
        if (!DoublePaymentCriteria.isSucceeded(refund)) {
            return new NeedsReview(CorrectionErrorCode.NO_REFUND, "은행 환불 SUCCEEDED 없음");
        }
        if (!DoublePaymentCriteria.isPosted(ledger)) {
            return new NeedsReview(CorrectionErrorCode.NO_CREDIT, "원장 적립 POSTED 없음");
        }
        return new NeedsReview(CorrectionErrorCode.AMOUNT_MISMATCH, "환불·적립 금액이 충전과 다름");
    }

    /**
     * 구조 검사는 대사와 같은 reversalProblems (CHARGE_REVERSAL_IN = 충전 금액, 회수 + 미수 = CHARGE_REVERSAL_IN 포함).
     * 저장할 회수·미수 금액은 항목에서 따로 더하는데, reversalProblems의 합은 long 덧셈이라
     * 넘침이 나면 여기서 이상으로 보고 충전 금액과 빼기로 다시 비교한다
     */
    private static Decision existingReversal(ChargeRequest charge, LedgerTransactionRecord reversal) {
        List<String> problems = DoublePaymentCriteria.reversalProblems(charge, reversal);
        if (!problems.isEmpty()) {
            return new NeedsReview(CorrectionErrorCode.REVERSAL_MALFORMED,
                    "기존 취소 분개 구조 이상 " + problems.size() + "건");
        }
        long recovered = 0;
        long receivable = 0;
        try {
            for (LedgerEntryRecord e : reversal.entries()) {
                if (REVERSAL_OUT.equals(e.entryType())) {
                    recovered = Math.addExact(recovered, e.amount());
                } else if (REVERSAL_RECEIVABLE.equals(e.entryType())) {
                    receivable = Math.addExact(receivable, e.amount());
                }
            }
        } catch (ArithmeticException e) {
            return new NeedsReview(CorrectionErrorCode.REVERSAL_MALFORMED, "기존 취소 분개 금액 넘침");
        }
        long amount = charge.getAmount();
        if (recovered < 0 || receivable < 0 || recovered > amount || receivable != amount - recovered) {
            return new NeedsReview(CorrectionErrorCode.REVERSAL_MALFORMED, "기존 취소 분개 회수+미수 ≠ 충전 금액");
        }
        return new AlreadyReversed(reversal.transactionId(), recovered, receivable);
    }

    private static String unavailableSources(SourceLookup<?> refund, SourceLookup<?> ledger, SourceLookup<?> reversal) {
        StringBuilder sb = new StringBuilder();
        append(sb, refund, "은행 환불");
        append(sb, ledger, "원장 적립");
        append(sb, reversal, "원장 취소 분개");
        return sb.toString();
    }

    private static void append(StringBuilder sb, SourceLookup<?> lookup, String name) {
        if (lookup instanceof SourceLookup.Unavailable<?>) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(name);
        }
    }
}
