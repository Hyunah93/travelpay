package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.reconciliation.MismatchType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 충전 1건 판정 (외부 호출·DB 없음). 처리 중 상태는 취소 분개가 있을 때만 넘어오며 REVERSAL_MISMATCH 하나로 끝난다.
 * <p>
 * 규칙마다 필요한 소스를 정하고, 필요한 소스가 모두 확인된(Found·NotFound) 규칙만 판정한다.
 * 조회 실패(Unavailable)로 판정할 수 없는 규칙은 보류하고 SOURCE_UNAVAILABLE 한 건에 실패 소스와 보류 규칙을 남긴다.
 * 확인된 사실만으로 정해지는 어긋남은 SOURCE_UNAVAILABLE과 함께 남는다.
 * <p>
 * 같은 유형이 여러 사유로 나오면 한 건으로 합치고 detail에 사유를 이어 적는다 ((run, charge, type) 중복 금지).
 * STATUS_MISMATCH의 사유는 "상태가 사실과 다름"과 "저장된 ID와 다름"으로 시작해 구분한다.
 * <p>
 * 충전 취소 분개: 이중 지급 사실(원장 적립 POSTED + 은행 환불 SUCCEEDED)이 있고 정상 취소 분개가 있으면
 * CREDIT_AND_REFUND_BOTH를 남기지 않는다. 취소 분개 구조가 틀리거나 이중 지급 사실이 없는 충전에 있으면 REVERSAL_MISMATCH.
 * 원거래 연결(originalTransactionId)은 원장 응답에 없어 확인하지 않는다 (원장 정합성 검사가 확인).
 */
public final class ChargeReconciliationJudge {

    static final String STATUS_DIFFERS = "상태가 사실과 다름";
    static final String ID_DIFFERS = "저장된 ID와 다름";

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

    private ChargeReconciliationJudge() {
    }

    public record Finding(MismatchType type, String detail) {
    }

    public static List<Finding> judge(ChargeRequest charge, ChargeFacts facts) {
        Collector out = new Collector(facts);
        switch (charge.getStatus()) {
            case MANUAL_REVIEW -> {
                out.add(MismatchType.REVIEW_REQUIRED, "MANUAL_REVIEW 충전: 판정하지 않고 사실만 기록");
                return out.findings();
            }
            case COMPLETED -> completed(charge, out);
            case FAILED -> failed(out);
            case REFUNDED -> refunded(charge, out);
            default -> {
                if (!facts.hasReversal()) {
                    throw new IllegalArgumentException("판정 대상이 아닌 상태: " + charge.getStatus());
                }
                out.add(MismatchType.REVERSAL_MISMATCH, "처리 중(" + charge.getStatus() + ") 충전에 취소 분개 있음");
                return out.findings();
            }
        }
        reversal(charge, out);
        storedIds(charge, out);
        return out.findings();
    }

    /** COMPLETED: 출금 SUCCEEDED + 원장 POSTED(지갑 CHARGE_IN 금액·통화 일치) + 환불 성공 없음 */
    private static void completed(ChargeRequest charge, Collector out) {
        out.withDebit("출금 성공 확인", debit -> {
            if (!isSucceeded(debit)) {
                out.add(MismatchType.CREDIT_WITHOUT_BANK_DEBIT,
                        "COMPLETED인데 은행 출금 성공 없음 (출금 " + bankStatus(debit) + ")");
            }
        });
        out.withLedger("원장 적립 확인", ledger -> {
            if (!isPosted(ledger)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT,
                        "COMPLETED인데 원장 적립(POSTED) 없음 (원장 " + ledgerStatus(ledger) + ")");
            }
        });
        out.withRefund("환불 없음 확인", refund -> {
            if (isSucceeded(refund)) {
                doublePayment(charge, out, "COMPLETED인데 은행 환불 성공 있음");
            }
        });
        out.withDebit("출금 금액", debit -> debitAmount(charge, debit, out));
        out.withLedger("원장 금액·통화", ledger -> ledgerAmount(charge, ledger, out));
    }

    /** FAILED: 출금 SUCCEEDED 없음 + 원장 거래 없음 (원장 거래가 있으면 REJECTED라도 정상 흐름에서 나올 수 없음) */
    private static void failed(Collector out) {
        out.withDebit("출금 성공 없음 확인", debit -> {
            if (isSucceeded(debit)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT, "FAILED인데 은행 출금 성공 있음");
            }
        });
        out.withLedger("원장 거래 없음 확인", ledger -> {
            if (ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found) {
                out.add(MismatchType.STATUS_MISMATCH, STATUS_DIFFERS + ": FAILED인데 원장 거래 있음 (원장 "
                        + found.record().status() + ")");
            }
        });
    }

    /** REFUNDED: 출금 SUCCEEDED + 원장 없음 또는 REJECTED + 환불 SUCCEEDED */
    private static void refunded(ChargeRequest charge, Collector out) {
        out.withDebit("출금 성공 확인", debit -> {
            if (!isSucceeded(debit)) {
                out.add(MismatchType.STATUS_MISMATCH, STATUS_DIFFERS + ": REFUNDED인데 은행 출금 성공 없음 (출금 "
                        + bankStatus(debit) + ")");
            }
        });
        out.withLedger("원장 적립 없음 확인", ledger -> {
            if (isPosted(ledger)) {
                doublePayment(charge, out, "REFUNDED인데 원장 적립(POSTED) 있음");
            }
        });
        out.withRefund("환불 성공 확인", refund -> {
            if (!isSucceeded(refund)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT,
                        "REFUNDED인데 은행 환불 성공 없음 (환불 " + bankStatus(refund) + ")");
            }
        });
        out.withDebit("출금 금액", debit -> debitAmount(charge, debit, out));
        out.withRefund("환불 금액", refund -> {
            if (refund instanceof SourceLookup.Found<BankTransactionRecord> found
                    && found.record().amount() != charge.getAmount()) {
                out.add(MismatchType.AMOUNT_MISMATCH, "은행 환불 금액(" + found.record().amount()
                        + ") ≠ 충전 금액(" + charge.getAmount() + ")");
            }
        });
    }

    /**
     * 이중 지급 의심 시: 취소 분개가 없으면 CREDIT_AND_REFUND_BOTH.
     * 있으면 이중 지급 사실(원장 적립 POSTED + 환불 SUCCEEDED)이 확인되고 취소 분개가 정상일 때만 남기지 않는다
     */
    private static void doublePayment(ChargeRequest charge, Collector out, String reason) {
        out.withReversal("이중 지급 취소 분개 확인", reversal -> {
            if (!(reversal instanceof SourceLookup.Found<LedgerTransactionRecord> found)) {
                out.add(MismatchType.CREDIT_AND_REFUND_BOTH, reason);
                return;
            }
            out.withRefund("이중 지급 취소 분개 확인", refund -> out.withLedger("이중 지급 취소 분개 확인", ledger -> {
                if (!isSucceeded(refund) || !isPosted(ledger) || !reversalProblems(charge, found.record()).isEmpty()) {
                    out.add(MismatchType.CREDIT_AND_REFUND_BOTH, reason + " (취소 분개가 이중 지급을 바로잡지 못함)");
                }
            }));
        });
    }

    /** 취소 분개가 있으면 구조와, 이중 지급 사실(원장 적립 POSTED + 환불 SUCCEEDED)이 있는 충전인지 확인한다 */
    private static void reversal(ChargeRequest charge, Collector out) {
        out.withReversal("취소 분개 확인", reversal -> {
            if (!(reversal instanceof SourceLookup.Found<LedgerTransactionRecord> found)) {
                return;
            }
            reversalProblems(charge, found.record()).forEach(p -> out.add(MismatchType.REVERSAL_MISMATCH, p));
            out.withRefund("취소 분개 이중 지급 확인", refund -> out.withLedger("취소 분개 이중 지급 확인", ledger -> {
                if (!isSucceeded(refund) || !isPosted(ledger)) {
                    out.add(MismatchType.REVERSAL_MISMATCH, "이중 지급 사실 없는 충전에 취소 분개 있음 (원장 적립 "
                            + ledgerStatus(ledger) + ", 은행 환불 " + bankStatus(refund) + ")");
                }
            }));
        });
    }

    /**
     * 정상 취소 분개: POSTED, referenceType CHARGE_REVERSAL, referenceId = chargeId,
     * CHARGE_SOURCE CREDIT CHARGE_REVERSAL_IN 1항목의 금액·통화 = 충전 금액·통화,
     * 지갑 DEBIT CHARGE_REVERSAL_OUT 합 + RECEIVABLE DEBIT CHARGE_REVERSAL_RECEIVABLE 합 = CHARGE_REVERSAL_IN 금액,
     * CHARGE_REVERSAL_OUT은 충전 지갑, 그 밖의 항목 없음. 어긋난 이유 목록 (정상이면 빈 목록)
     */
    private static List<String> reversalProblems(ChargeRequest charge, LedgerTransactionRecord reversal) {
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

    private static void debitAmount(ChargeRequest charge, SourceLookup<BankTransactionRecord> debit, Collector out) {
        if (debit instanceof SourceLookup.Found<BankTransactionRecord> found
                && found.record().amount() != charge.getAmount()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "은행 출금 금액(" + found.record().amount()
                    + ") ≠ 충전 금액(" + charge.getAmount() + ")");
        }
    }

    /** POSTED 원장에서 USER·CREDIT·CHARGE_IN이고 walletId가 충전 지갑인 항목의 금액·통화 = 충전 금액·통화 */
    private static void ledgerAmount(ChargeRequest charge, SourceLookup<LedgerTransactionRecord> ledger,
                                     Collector out) {
        if (!(ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found) || !found.record().isPosted()) {
            return;
        }
        Optional<LedgerEntryRecord> entry = found.record().chargeInEntry(charge.getWalletId());
        if (entry.isEmpty()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장에 지갑 적립 항목(USER·CREDIT·CHARGE_IN, walletId="
                    + charge.getWalletId() + ") 없음");
            return;
        }
        if (entry.get().amount() != charge.getAmount()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장 적립 금액(" + entry.get().amount()
                    + ") ≠ 충전 금액(" + charge.getAmount() + ")");
        }
        if (!charge.getCurrency().name().equals(entry.get().currency())) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장 적립 통화(" + entry.get().currency()
                    + ") ≠ 충전 통화(" + charge.getCurrency() + ")");
        }
    }

    /**
     * 충전에 저장된 ID와 찾은 기록의 ID 비교. 기대값은 성공 기록(출금·환불 SUCCEEDED, 원장 POSTED)의 ID, 없으면 null.
     * 성공 기록이 없는데 ID가 저장돼 있거나, 성공 기록이 있는데 저장된 ID가 없거나 다르면 STATUS_MISMATCH
     */
    private static void storedIds(ChargeRequest charge, Collector out) {
        out.withDebit("bank_tx_id 일치", debit ->
                compareId(out, "bank_tx_id", charge.getBankTxId(), succeededTxId(debit)));
        out.withRefund("refund_bank_tx_id 일치", refund ->
                compareId(out, "refund_bank_tx_id", charge.getRefundBankTxId(), succeededTxId(refund)));
        out.withLedger("ledger_ref 일치", ledger -> {
            UUID expected = ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found
                    && found.record().isPosted() ? found.record().transactionId() : null;
            compareId(out, "ledger_ref", charge.getLedgerRef(), expected);
        });
    }

    private static void compareId(Collector out, String column, UUID stored, UUID found) {
        if (!Objects.equals(stored, found)) {
            out.add(MismatchType.STATUS_MISMATCH,
                    ID_DIFFERS + ": " + column + " (저장 " + stored + ", 기록 " + found + ")");
        }
    }

    private static UUID succeededTxId(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found && found.record().isSucceeded()
                ? found.record().bankTxId() : null;
    }

    private static boolean isSucceeded(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found && found.record().isSucceeded();
    }

    private static boolean isPosted(SourceLookup<LedgerTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<LedgerTransactionRecord> found && found.record().isPosted();
    }

    private static String bankStatus(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found ? found.record().status() : "없음";
    }

    private static String ledgerStatus(SourceLookup<LedgerTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<LedgerTransactionRecord> found ? found.record().status() : "없음";
    }

    private interface Rule<T> {
        void apply(SourceLookup<T> lookup);
    }

    /** 규칙 실행기. 필요한 소스가 Unavailable이면 규칙을 보류하고 기록한다 */
    private static final class Collector {

        private final ChargeFacts facts;
        private final Map<MismatchType, List<String>> reasons = new EnumMap<>(MismatchType.class);
        /** 소스 이름 → (실패 사유, 보류 규칙들) */
        private final Map<String, Held> held = new LinkedHashMap<>();

        private Collector(ChargeFacts facts) {
            this.facts = facts;
        }

        void add(MismatchType type, String reason) {
            reasons.computeIfAbsent(type, k -> new ArrayList<>()).add(reason);
        }

        void withDebit(String rule, Rule<BankTransactionRecord> body) {
            with("은행 출금", facts.debit(), rule, body);
        }

        void withRefund(String rule, Rule<BankTransactionRecord> body) {
            with("은행 환불", facts.refund(), rule, body);
        }

        void withLedger(String rule, Rule<LedgerTransactionRecord> body) {
            with("원장", facts.ledger(), rule, body);
        }

        void withReversal(String rule, Rule<LedgerTransactionRecord> body) {
            with("원장 취소 분개", facts.reversal(), rule, body);
        }

        private <T> void with(String source, SourceLookup<T> lookup, String rule, Rule<T> body) {
            if (lookup instanceof SourceLookup.Unavailable<T> unavailable) {
                held.computeIfAbsent(source, k -> new Held(unavailable.reason(), new ArrayList<>())).rules().add(rule);
                return;
            }
            body.apply(lookup);
        }

        List<Finding> findings() {
            if (!held.isEmpty()) {
                List<String> parts = new ArrayList<>();
                held.forEach((source, h) -> parts.add(source + " 조회 실패(" + h.reason() + "), 보류: "
                        + String.join(", ", h.rules())));
                add(MismatchType.SOURCE_UNAVAILABLE, String.join(" / ", parts));
            }
            List<Finding> findings = new ArrayList<>();
            reasons.forEach((type, list) -> findings.add(new Finding(type, String.join("; ", list))));
            return findings;
        }

        private record Held(String reason, List<String> rules) {
        }
    }
}
