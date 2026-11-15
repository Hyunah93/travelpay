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

    private LedgerIntegritySql() {
    }
}
