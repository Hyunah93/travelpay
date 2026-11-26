package com.travelpay.ledger.infra.persistence.integrity;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.integrity.AccountBalanceViolation;
import com.travelpay.ledger.domain.integrity.ChargeReversalViolation;
import com.travelpay.ledger.domain.integrity.CurrencyTotalViolation;
import com.travelpay.ledger.domain.integrity.IntegrityCheck;
import com.travelpay.ledger.domain.integrity.LedgerIntegrityRepository;
import com.travelpay.ledger.domain.integrity.RejectedEntriesViolation;
import com.travelpay.ledger.domain.integrity.TransactionBalanceViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 정합성 검사 SQL 실행. 서비스 트랜잭션(JPA)에 묶인 같은 연결에서 실행되고, 트랜잭션 timeout이 문장 timeout으로 걸린다.
 */
@Repository
@RequiredArgsConstructor
public class LedgerIntegrityRepositoryImpl implements LedgerIntegrityRepository {

    /**
     * 트랜잭션 advisory lock 키 (정수 두 개 형태). 첫 값은 원장("LEDG"), 둘째 값은 원장 안의 용도(1 = 정합성 검사).
     * Flyway(12.4.0 PostgreSQLAdvisoryLockTemplate)는 bigint 하나 형태(매직 넘버 + 기록 테이블 이름 hashCode)를 쓰고,
     * PostgreSQL은 bigint 하나와 정수 두 개의 키 공간이 겹치지 않는다(pg_locks objsubid 1과 2로 구분).
     */
    public static final int LOCK_KEY_LEDGER = 0x4C454447;
    public static final int LOCK_KEY_INTEGRITY_CHECK = 1;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean tryLockForCheck() {
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                LOCK_KEY_LEDGER, LOCK_KEY_INTEGRITY_CHECK);
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public IntegrityCheck<AccountBalanceViolation> checkAccountBalances(int violationLimit) {
        return run(LedgerIntegritySql.ACCOUNT_BALANCES, violationLimit, rs -> new AccountBalanceViolation(
                rs.getObject("id", UUID.class), currency(rs, "currency"), rs.getLong("balance"),
                integer(rs, "net")));
    }

    @Override
    public IntegrityCheck<TransactionBalanceViolation> checkPostedTransactionBalances(int violationLimit) {
        return run(LedgerIntegritySql.POSTED_TRANSACTION_BALANCES, violationLimit,
                rs -> new TransactionBalanceViolation(rs.getObject("transaction_id", UUID.class),
                        currency(rs, "currency"), integer(rs, "debit_sum"), integer(rs, "credit_sum")));
    }

    @Override
    public IntegrityCheck<RejectedEntriesViolation> checkRejectedTransactionsHaveNoEntries(int violationLimit) {
        return run(LedgerIntegritySql.REJECTED_WITHOUT_ENTRIES, violationLimit, rs -> new RejectedEntriesViolation(
                rs.getObject("id", UUID.class), rs.getLong("entry_count")));
    }

    @Override
    public IntegrityCheck<CurrencyTotalViolation> checkCurrencyTotals(int violationLimit) {
        return run(LedgerIntegritySql.CURRENCY_TOTALS, violationLimit, rs -> new CurrencyTotalViolation(
                currency(rs, "currency"), integer(rs, "total")));
    }

    @Override
    public IntegrityCheck<ChargeReversalViolation> checkChargeReversalStructure(int violationLimit) {
        return run(LedgerIntegritySql.CHARGE_REVERSAL_STRUCTURE, violationLimit, rs -> new ChargeReversalViolation(
                rs.getObject("transaction_id", UUID.class),
                ChargeReversalViolation.Reason.valueOf(rs.getString("reason"))));
    }

    /** 첫 행에서 검사 대상 수·전체 위반 수를 읽고, 위반 열(3번째)이 NULL이 아닌 행만 위반으로 모은다 */
    private <T> IntegrityCheck<T> run(String sql, int violationLimit, ViolationMapper<T> mapper) {
        return jdbcTemplate.query(sql, rs -> {
            long checked = 0;
            long violationCount = 0;
            List<T> violations = new ArrayList<>();
            boolean first = true;
            while (rs.next()) {
                if (first) {
                    checked = rs.getLong("checked_count");
                    violationCount = rs.getLong("violation_count");
                    first = false;
                }
                if (rs.getObject(3) != null) {
                    violations.add(mapper.map(rs));
                }
            }
            return new IntegrityCheck<>(checked, violationCount, violations);
        }, violationLimit);
    }

    private static Currency currency(ResultSet rs, String column) throws SQLException {
        return Currency.valueOf(rs.getString(column).trim());
    }

    /** SUM(bigint)은 numeric이라 정수로 그대로 옮긴다 */
    private static BigInteger integer(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).toBigIntegerExact();
    }

    @FunctionalInterface
    private interface ViolationMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
