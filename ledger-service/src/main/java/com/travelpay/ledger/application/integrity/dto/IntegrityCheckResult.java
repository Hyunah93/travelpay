package com.travelpay.ledger.application.integrity.dto;

import com.travelpay.ledger.domain.integrity.AccountBalanceViolation;
import com.travelpay.ledger.domain.integrity.ChargeReversalViolation;
import com.travelpay.ledger.domain.integrity.CurrencyTotalViolation;
import com.travelpay.ledger.domain.integrity.IntegrityCheck;
import com.travelpay.ledger.domain.integrity.RejectedEntriesViolation;
import com.travelpay.ledger.domain.integrity.TransactionBalanceViolation;

import java.time.Instant;
import java.util.List;

/**
 * 정합성 검사 결과. 검사마다 SQL 한 문장이라 검사끼리는 읽은 시점이 다를 수 있다(전체가 한 시점의 스냅샷은 아님).
 *
 * @param checkedAt      첫 검사를 시작하기 직전 시각
 * @param violationLimit 검사별로 돌려주는 위반 최대 건수
 */
public record IntegrityCheckResult(
        Instant checkedAt,
        int violationLimit,
        CheckOutcome<AccountBalanceViolation> accountBalance,
        CheckOutcome<TransactionBalanceViolation> postedTransactionBalance,
        CheckOutcome<RejectedEntriesViolation> rejectedTransactionEntries,
        CheckOutcome<CurrencyTotalViolation> currencyTotal,
        CheckOutcome<ChargeReversalViolation> chargeReversalStructure
) {

    public long totalViolationCount() {
        return accountBalance.violationCount() + postedTransactionBalance.violationCount()
                + rejectedTransactionEntries.violationCount() + currencyTotal.violationCount()
                + chargeReversalStructure.violationCount();
    }

    /**
     * 검사 하나의 결과.
     *
     * @param checkedCount   실제로 검사한 계정 또는 거래 수 (검사 SQL에서 집계)
     * @param violationCount 전체 위반 건수
     * @param returnedCount  violations에 담은 건수
     * @param truncated      전체 위반 중 일부만 담았으면 true
     * @param violations     정렬 후 앞쪽부터 최대 violationLimit건
     */
    public record CheckOutcome<T>(long checkedCount, long violationCount, int returnedCount, boolean truncated,
                                  List<T> violations) {

        public CheckOutcome {
            violations = List.copyOf(violations);
        }

        public static <T> CheckOutcome<T> from(IntegrityCheck<T> check) {
            int returned = check.violations().size();
            return new CheckOutcome<>(check.checkedCount(), check.violationCount(), returned,
                    check.violationCount() > returned, check.violations());
        }
    }
}
