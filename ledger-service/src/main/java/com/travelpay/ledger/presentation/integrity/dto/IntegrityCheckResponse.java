package com.travelpay.ledger.presentation.integrity.dto;

import com.travelpay.ledger.application.integrity.dto.IntegrityCheckResult;
import com.travelpay.ledger.application.integrity.dto.IntegrityCheckResult.CheckOutcome;
import com.travelpay.ledger.domain.integrity.AccountBalanceViolation;
import com.travelpay.ledger.domain.integrity.ChargeReversalViolation;
import com.travelpay.ledger.domain.integrity.CurrencyTotalViolation;
import com.travelpay.ledger.domain.integrity.RejectedEntriesViolation;
import com.travelpay.ledger.domain.integrity.TransactionBalanceViolation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 정합성 검사 응답. 검사마다 SQL 한 문장이라 검사끼리는 읽은 시점이 다를 수 있다.
 * consistent는 다섯 검사의 위반이 모두 0일 때 true.
 */
public record IntegrityCheckResponse(
        Instant checkedAt,
        boolean consistent,
        long totalViolationCount,
        int violationLimit,
        Check<AccountBalance> accountBalance,
        Check<TransactionBalance> postedTransactionBalance,
        Check<RejectedEntries> rejectedTransactionEntries,
        Check<CurrencyTotal> currencyTotal,
        Check<ChargeReversalStructure> chargeReversalStructure
) {

    public static IntegrityCheckResponse from(IntegrityCheckResult result) {
        long total = result.totalViolationCount();
        return new IntegrityCheckResponse(result.checkedAt(), total == 0, total, result.violationLimit(),
                Check.from(result.accountBalance(), AccountBalance::from),
                Check.from(result.postedTransactionBalance(), TransactionBalance::from),
                Check.from(result.rejectedTransactionEntries(), RejectedEntries::from),
                Check.from(result.currencyTotal(), CurrencyTotal::from),
                Check.from(result.chargeReversalStructure(), ChargeReversalStructure::from));
    }

    public record Check<T>(long checkedCount, long violationCount, int returnedCount, boolean truncated,
                           List<T> violations) {

        static <S, T> Check<T> from(CheckOutcome<S> outcome, Function<S, T> mapper) {
            return new Check<>(outcome.checkedCount(), outcome.violationCount(), outcome.returnedCount(),
                    outcome.truncated(), outcome.violations().stream().map(mapper).toList());
        }
    }

    public record AccountBalance(UUID accountId, String currency, long balance, BigInteger postedNet) {
        static AccountBalance from(AccountBalanceViolation v) {
            return new AccountBalance(v.accountId(), v.currency().name(), v.balance(), v.postedNet());
        }
    }

    public record TransactionBalance(UUID transactionId, String currency, BigInteger debitSum, BigInteger creditSum) {
        static TransactionBalance from(TransactionBalanceViolation v) {
            return new TransactionBalance(v.transactionId(), v.currency().name(), v.debitSum(), v.creditSum());
        }
    }

    public record RejectedEntries(UUID transactionId, long entryCount) {
        static RejectedEntries from(RejectedEntriesViolation v) {
            return new RejectedEntries(v.transactionId(), v.entryCount());
        }
    }

    public record CurrencyTotal(String currency, BigInteger totalBalance) {
        static CurrencyTotal from(CurrencyTotalViolation v) {
            return new CurrencyTotal(v.currency().name(), v.totalBalance());
        }
    }

    /** reason: ORIGINAL_MISMATCH, REVERSAL_IN_MISMATCH, SPLIT_MISMATCH, WALLET_MISMATCH, RECEIVABLE_OUTSIDE_REVERSAL */
    public record ChargeReversalStructure(UUID transactionId, String reason) {
        static ChargeReversalStructure from(ChargeReversalViolation v) {
            return new ChargeReversalStructure(v.transactionId(), v.reason().name());
        }
    }
}
