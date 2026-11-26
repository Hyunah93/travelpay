package com.travelpay.ledger.infra.persistence.integrity;

/**
 * 정합성 검사 SQL. 검사마다 한 문장이라 READ COMMITTED에서도 문장 안의 모든 테이블을 같은 시점으로 읽는다
 * (계정 잔액과 항목 합계를 다른 시점에 읽어 생기는 오탐이 없다). 검사끼리는 시점이 다를 수 있다.
 * <p>
 * 결과는 항상 1행 이상: 첫 두 열은 검사 대상 수와 전체 위반 수, 나머지는 위반 한 건(없으면 NULL 1행).
 * 위반만 모은 CTE를 두 번 읽으므로(건수·앞쪽 N건) 그 CTE만 구체화되고, 전체 집계 결과는 구체화되지 않는다.
 * 마지막 ? 는 돌려줄 위반 최대 건수. 성능 측정에서도 같은 문장을 쓴다.
 */
public final class LedgerIntegritySql {

    /** 1. 계정 잔액 = POSTED 항목의 (CREDIT 합 - DEBIT 합). 모든 계정의 시작 잔액이 0이라 보정 없이 비교한다 */
    public static final String ACCOUNT_BALANCES = """
            WITH posted_net AS (
                SELECT e.account_id,
                       SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) AS net
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE t.status = 'POSTED'
                GROUP BY e.account_id
            ), violations AS (
                SELECT a.id, a.currency, a.balance, COALESCE(p.net, 0) AS net
                FROM ledger_accounts a
                LEFT JOIN posted_net p ON p.account_id = a.id
                WHERE a.balance <> COALESCE(p.net, 0)
            )
            SELECT (SELECT count(*) FROM ledger_accounts) AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.id, v.currency, v.balance, v.net
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (SELECT * FROM violations ORDER BY id LIMIT ?) AS v ON true
            """;

    /** 2. POSTED 거래마다 통화별 DEBIT 합 = CREDIT 합. 항목이 없는 POSTED 거래는 합이 0=0이라 위반이 아니다 */
    public static final String POSTED_TRANSACTION_BALANCES = """
            WITH violations AS (
                SELECT e.transaction_id, e.currency,
                       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END) AS debit_sum,
                       SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END) AS credit_sum
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE t.status = 'POSTED'
                GROUP BY e.transaction_id, e.currency
                HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END)
                       <> SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END)
            )
            SELECT (SELECT count(*) FROM ledger_transactions WHERE status = 'POSTED') AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.transaction_id, v.currency, v.debit_sum, v.credit_sum
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (
                SELECT * FROM violations ORDER BY transaction_id, currency LIMIT ?
            ) AS v ON true
            """;

    /** 3. REJECTED 거래에 항목이 없음 */
    public static final String REJECTED_WITHOUT_ENTRIES = """
            WITH violations AS (
                SELECT t.id, count(*) AS entry_count
                FROM ledger_transactions t
                JOIN ledger_entries e ON e.transaction_id = t.id
                WHERE t.status = 'REJECTED'
                GROUP BY t.id
            )
            SELECT (SELECT count(*) FROM ledger_transactions WHERE status = 'REJECTED') AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.id, v.entry_count
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (SELECT * FROM violations ORDER BY id LIMIT ?) AS v ON true
            """;

    /** 4. 통화별 전체 계정 잔액 합 = 0. 어긋남 여부만 알려 주고 어느 계정인지는 1로 찾는다 */
    public static final String CURRENCY_TOTALS = """
            WITH totals AS (
                SELECT currency, count(*) AS account_count, SUM(balance) AS total
                FROM ledger_accounts
                GROUP BY currency
            ), violations AS (
                SELECT currency, total FROM totals WHERE total <> 0
            )
            SELECT (SELECT COALESCE(SUM(account_count), 0) FROM totals) AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.currency, v.total
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (SELECT * FROM violations ORDER BY currency LIMIT ?) AS v ON true
            """;

    /**
     * 5. 충전 취소 분개 구조. POSTED CHARGE_REVERSAL 거래마다 원거래 연결·금액·지갑을 확인하고,
     * 미수(RECEIVABLE) 계정에 취소 분개가 아닌 거래의 항목이 있는지도 함께 찾는다.
     * 위반 한 건은 (거래, 이유). 한 거래가 여러 이유로 걸리면 이유마다 한 건
     * <ul>
     *     <li>ORIGINAL_MISMATCH: original_transaction_id가 POSTED CHARGE 거래가 아니거나 그 reference_id가 chargeId와 다름</li>
     *     <li>REVERSAL_IN_MISMATCH: CHARGE_SOURCE CREDIT CHARGE_REVERSAL_IN 금액·통화가 원거래 지갑 적립(USER CREDIT CHARGE_IN)과 다름
     *         (어느 쪽이든 정확히 1항목이 아니어도 위반)</li>
     *     <li>SPLIT_MISMATCH: 지갑 DEBIT CHARGE_REVERSAL_OUT + RECEIVABLE DEBIT CHARGE_REVERSAL_RECEIVABLE ≠ CHARGE_REVERSAL_IN 금액</li>
     *     <li>WALLET_MISMATCH: CHARGE_REVERSAL_OUT 항목이 원거래 CHARGE_IN과 다른 계정</li>
     *     <li>RECEIVABLE_OUTSIDE_REVERSAL: RECEIVABLE 계정 항목이 CHARGE_REVERSAL이 아닌 거래에 있음 (거래 상태 무관)</li>
     * </ul>
     * 취소 분개는 reference_type 조건으로 V6 인덱스(reference_type, created_at, id)에서 찾고, 항목은 transaction_id 인덱스,
     * 미수 계정 항목은 (account_id, created_at) 인덱스로 읽는다. checked_count는 검사한 POSTED CHARGE_REVERSAL 거래 수
     */
    public static final String CHARGE_REVERSAL_STRUCTURE = """
            WITH reversals AS (
                SELECT r.id, o.id AS original_id,
                       (o.status = 'POSTED' AND o.reference_type = 'CHARGE'
                        AND o.reference_id = r.reference_id) IS TRUE AS original_linked
                FROM ledger_transactions r
                LEFT JOIN ledger_transactions o ON o.id = r.original_transaction_id
                WHERE r.reference_type = 'CHARGE_REVERSAL' AND r.status = 'POSTED'
            ), measured AS (
                SELECT r.id, r.original_linked,
                       ci.line_count AS charge_in_lines, ci.amount AS charge_amount,
                       rv.in_lines, rv.in_amount, rv.in_currency_ok, rv.out_amount, rv.receivable_amount,
                       rv.out_other_account
                FROM reversals r
                JOIN LATERAL (
                    SELECT count(*) AS line_count, MIN(e.amount) AS amount, MIN(e.currency) AS currency,
                           (array_agg(e.account_id))[1] AS account_id
                    FROM ledger_entries e
                    JOIN ledger_accounts a ON a.id = e.account_id
                    WHERE e.transaction_id = r.original_id
                      AND e.entry_type = 'CHARGE_IN' AND e.direction = 'CREDIT' AND a.owner_type = 'USER'
                ) AS ci ON true
                JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE e.entry_type = 'CHARGE_REVERSAL_IN') AS in_lines,
                           COALESCE(SUM(e.amount) FILTER (
                               WHERE e.entry_type = 'CHARGE_REVERSAL_IN' AND e.direction = 'CREDIT'
                                 AND a.owner_type = 'SYSTEM' AND a.account_type = 'CHARGE_SOURCE'), 0) AS in_amount,
                           bool_and(e.currency = ci.currency)
                               FILTER (WHERE e.entry_type = 'CHARGE_REVERSAL_IN') AS in_currency_ok,
                           COALESCE(SUM(e.amount) FILTER (
                               WHERE e.entry_type = 'CHARGE_REVERSAL_OUT' AND e.direction = 'DEBIT'
                                 AND a.owner_type = 'USER' AND a.account_type = 'WALLET'), 0) AS out_amount,
                           COALESCE(SUM(e.amount) FILTER (
                               WHERE e.entry_type = 'CHARGE_REVERSAL_RECEIVABLE' AND e.direction = 'DEBIT'
                                 AND a.owner_type = 'SYSTEM' AND a.account_type = 'RECEIVABLE'), 0)
                               AS receivable_amount,
                           count(*) FILTER (WHERE e.entry_type = 'CHARGE_REVERSAL_OUT'
                                              AND e.account_id IS DISTINCT FROM ci.account_id) AS out_other_account
                    FROM ledger_entries e
                    JOIN ledger_accounts a ON a.id = e.account_id
                    WHERE e.transaction_id = r.id
                ) AS rv ON true
            ), violations AS (
                SELECT id AS transaction_id, 'ORIGINAL_MISMATCH' AS reason
                FROM measured WHERE NOT original_linked
                UNION ALL
                SELECT id, 'REVERSAL_IN_MISMATCH'
                FROM measured
                WHERE NOT (charge_in_lines = 1 AND in_lines = 1 AND in_amount = charge_amount
                           AND in_currency_ok IS TRUE)
                UNION ALL
                SELECT id, 'SPLIT_MISMATCH'
                FROM measured WHERE out_amount + receivable_amount <> in_amount
                UNION ALL
                SELECT id, 'WALLET_MISMATCH'
                FROM measured WHERE out_other_account > 0
                UNION ALL
                SELECT DISTINCT e.transaction_id, 'RECEIVABLE_OUTSIDE_REVERSAL'
                FROM ledger_accounts a
                JOIN ledger_entries e ON e.account_id = a.id
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE a.owner_type = 'SYSTEM' AND a.account_type = 'RECEIVABLE'
                  AND t.reference_type <> 'CHARGE_REVERSAL'
            )
            SELECT (SELECT count(*) FROM ledger_transactions
                    WHERE reference_type = 'CHARGE_REVERSAL' AND status = 'POSTED') AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.transaction_id, v.reason
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (
                SELECT * FROM violations ORDER BY transaction_id, reason LIMIT ?
            ) AS v ON true
            """;

    private LedgerIntegritySql() {
    }
}
