package com.travelpay.ledger;

import com.travelpay.ledger.infra.persistence.integrity.LedgerIntegritySql;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 정합성 검사 성능 측정. 기본 test에서 빠지고 performanceTest로만 실행한다. 인덱스는 추가하지 않는다.
 * <p>
 * 데이터는 LedgerTransactionPeriodPerformanceTest의 생성 방식(회원 1만 명, 유형 CHARGE 4·PAYMENT 3·TRANSFER 2·EXCHANGE 1,
 * created_at 1년 균등)을 따르고 다음만 다르다.
 * <ul>
 *     <li>REJECTED 거래: 행 번호 % 50 = 4(PAYMENT 자리)인 거래를 REJECTED(INSUFFICIENT_BALANCE)로 넣고 항목을 만들지 않는다
 *         (전체의 2%, PAYMENT의 약 6.7%). PAYMENT만 고른 이유: 빠진 결제는 사용자 잔액을 늘리기만 해 USER 음수가 생기지 않는다</li>
 *     <li>송금 상대: 원래 (n + 1) % 회원 수. 1만 규모는 회원 0~999만 거래가 있어 회원 0이 송금을 받지 못해 KRW 순합계가
 *         -3,100이 된다(USER 잔액 음수 CHECK 위반). 그래서 (n + 1) % 활동 회원 수(min(회원 수, 규모 / 10))로 바꿨다.
 *         10만·120만은 활동 회원 수 = 회원 수라 원래와 같다</li>
 *     <li>준비 단계: 잔액 맞춤 전에 계정별 POSTED 순합계가 음수인 USER 계정을 조회해 기록하고, 있으면 멈춘다.
 *         그 뒤 "계정 잔액 = POSTED 항목 합"으로 맞춘다(측정 데이터 준비에서만)</li>
 * </ul>
 * 규모마다 ANALYZE 뒤 API로 위반 0(오탐 0)을 확인하고, 검사 SQL 네 개를 EXPLAIN (ANALYZE, BUFFERS)로 6회(첫 회 제외 5회 중앙값),
 * API 응답을 6회(첫 회 제외 5회 중앙값) 측정한다. 검사 1·2는 디스크 사용(Disk, temp read/written) 줄과 전체 계획을 남긴다.
 */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "travelpay.ledger.integrity.timeout-seconds=" + LedgerIntegrityCheckPerformanceTest.TIMEOUT_SECONDS,
        "travelpay.ledger.integrity.violation-limit=" + LedgerIntegrityCheckPerformanceTest.VIOLATION_LIMIT
})
class LedgerIntegrityCheckPerformanceTest {

    static final int TIMEOUT_SECONDS = 600;
    static final int VIOLATION_LIMIT = 100;
    private static final String SCHEMA = "ledger_service";
    private static final String INTEGRITY = "/internal/v1/ledger/integrity";
    private static final int[] SCALES = {10_000, 100_000, 1_200_000};
    private static final int USERS = 10_000;
    private static final int RUNS = 5;
    private static final String YEAR_START = "2025-01-01T00:00:00Z";
    private static final Map<String, String> CHECK_SQL = new LinkedHashMap<>();

    static {
        CHECK_SQL.put("1 계정 잔액", LedgerIntegritySql.ACCOUNT_BALANCES);
        CHECK_SQL.put("2 거래 차대", LedgerIntegritySql.POSTED_TRANSACTION_BALANCES);
        CHECK_SQL.put("3 거절 거래 항목", LedgerIntegritySql.REJECTED_WITHOUT_ENTRIES);
        CHECK_SQL.put("4 통화별 합계", LedgerIntegritySql.CURRENCY_TOTALS);
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA)
            .withCommand("postgres", "-c", "fsync=off", "-c", "autovacuum=off");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final StringBuilder report = new StringBuilder();

    @Test
    void 정합성_검사_규모별_측정() throws Exception {
        line("## 환경");
        line(jdbcTemplate.queryForObject("select version()", String.class));
        for (String setting : List.of("shared_buffers", "work_mem", "hash_mem_multiplier", "jit",
                "max_parallel_workers_per_gather", "autovacuum")) {
            line(setting + " = " + jdbcTemplate.queryForObject("show " + setting, String.class));
        }
        line("Docker 호스트 CPU = " + Runtime.getRuntime().availableProcessors() + " (JVM 기준)");
        line("위반 최대 건수(LIMIT) = " + VIOLATION_LIMIT + ", 검사 timeout = " + TIMEOUT_SECONDS + "s");
        createAccounts();

        for (int scale : SCALES) {
            measureScale(scale);
        }
        System.out.println("\n===== PERF REPORT =====\n" + report + "===== END PERF REPORT =====");
    }

    private void measureScale(int scale) throws Exception {
        line("");
        line("## 규모 " + scale);
        long start = System.nanoTime();
        generate(scale);
        line("데이터 생성(TRUNCATE 후 재생성) " + elapsedMs(start) + " ms");

        List<String> negatives = negativeUserAccounts();
        line("잔액 맞춤 전 POSTED 순합계가 음수인 USER 계정: " + negatives.size() + "건"
                + (negatives.isEmpty() ? "" : " " + negatives));
        if (!negatives.isEmpty()) {
            System.out.println("\n===== PERF REPORT (중단) =====\n" + report + "===== END PERF REPORT =====");
            fail("USER 계정 순합계가 음수라 잔액을 맞출 수 없음: " + negatives);
        }

        start = System.nanoTime();
        alignBalances();
        line("잔액 맞춤(+ VACUUM ANALYZE ledger_accounts) " + elapsedMs(start) + " ms");
        analyze();

        long transactions = count("SELECT count(*) FROM ledger_transactions");
        long rejected = count("SELECT count(*) FROM ledger_transactions WHERE status = 'REJECTED'");
        long entries = count("SELECT count(*) FROM ledger_entries");
        line("거래 " + transactions + " (REJECTED " + rejected + ", "
                + String.format("%.2f", rejected * 100.0 / transactions) + "%) / 항목 " + entries + " (거래당 "
                + String.format("%.2f", entries * 1.0 / (transactions - rejected)) + ", POSTED 기준) / 계정 "
                + count("SELECT count(*) FROM ledger_accounts"));
        line("유형·상태 분포: " + jdbcTemplate.queryForList("""
                SELECT reference_type || '/' || status || '=' || count(*) FROM ledger_transactions
                GROUP BY reference_type, status ORDER BY reference_type, status
                """, String.class));
        line("테이블 크기: entries " + size("ledger_entries") + ", transactions " + size("ledger_transactions")
                + ", accounts " + size("ledger_accounts"));

        HttpResponse<String> first = get();
        assertEquals(200, first.statusCode(), first.body());
        JsonNode data = jsonMapper.readTree(first.body()).get("data");
        line("API 결과(오탐 확인): consistent=" + data.get("consistent").asBoolean() + ", 전체 위반 "
                + data.get("totalViolationCount").asLong() + ", 검사 대상 수 1=" + checked(data, "accountBalance")
                + " 2=" + checked(data, "postedTransactionBalance") + " 3=" + checked(data, "rejectedTransactionEntries")
                + " 4=" + checked(data, "currencyTotal"));
        assertTrue(data.get("consistent").asBoolean(), "정상 데이터에서 위반이 나옴(오탐): " + first.body());

        line("");
        line("### " + scale + " / 검사 SQL (EXPLAIN ANALYZE Execution Time, 5회 중앙값)");
        for (Map.Entry<String, String> check : CHECK_SQL.entrySet()) {
            Explain explain = explainMedian(check.getValue().replace("LIMIT ?", "LIMIT " + VIOLATION_LIMIT));
            line("- " + check.getKey() + ": " + explain.median() + " / 5회 " + explain.runs());
            line("  - 계획: " + shape(explain.plan()));
            line("  - 디스크 사용: " + disk(explain.plan()));
            if (check.getKey().startsWith("1") || check.getKey().startsWith("2")) {
                System.out.println("---- 전체 계획: " + scale + " / " + check.getKey() + "\n" + explain.plan());
            }
        }
        Api api = apiMedian();
        line("- API 응답(네 검사 + 잠금): " + api.median() + " / 5회 " + api.runs() + ", 상태 " + api.statuses());
    }

    // =====================================================================
    // 데이터 생성 (LedgerTransactionPeriodPerformanceTest와 같은 방식, 차이는 클래스 주석)
    // =====================================================================

    private String chargeSource;
    private String merchant;
    private String fxKrw;
    private String fxJpy;
    private String feeKrw;

    private void createAccounts() {
        jdbcTemplate.execute("""
                CREATE TABLE perf_wallets (n INT PRIMARY KEY, user_id UUID NOT NULL, krw UUID NOT NULL,
                                           jpy UUID NOT NULL);
                INSERT INTO perf_wallets (n, user_id, krw, jpy)
                SELECT g, gen_random_uuid(), gen_random_uuid(), gen_random_uuid() FROM generate_series(0, %d) g;
                INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type)
                SELECT krw, 'USER', user_id, 'KRW', 'WALLET' FROM perf_wallets
                UNION ALL SELECT jpy, 'USER', user_id, 'JPY', 'WALLET' FROM perf_wallets;
                INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type)
                VALUES (gen_random_uuid(), 'MERCHANT', NULL, 'KRW', 'MERCHANT_SETTLEMENT');
                ANALYZE ledger_accounts;
                ANALYZE perf_wallets;
                """.formatted(USERS - 1));
        chargeSource = systemAccount("CHARGE_SOURCE", "KRW");
        fxKrw = systemAccount("FX_POSITION", "KRW");
        fxJpy = systemAccount("FX_POSITION", "JPY");
        feeKrw = systemAccount("FEE_REVENUE", "KRW");
        merchant = jdbcTemplate.queryForObject(
                "SELECT id::text FROM ledger_accounts WHERE account_type = 'MERCHANT_SETTLEMENT'", String.class);
    }

    private String systemAccount(String type, String currency) {
        return jdbcTemplate.queryForObject("""
                SELECT id::text FROM ledger_accounts
                WHERE owner_type = 'SYSTEM' AND account_type = ? AND currency = ?
                """, String.class, type, currency);
    }

    private void generate(int scale) {
        int activeUsers = Math.min(USERS, scale / 10);
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("TRUNCATE ledger_entries, ledger_transactions");
                st.execute("UPDATE ledger_accounts SET balance = 0 WHERE balance <> 0");
                st.execute("SELECT setseed(0.42)");
                st.execute("""
                        INSERT INTO ledger_transactions
                            (id, reference_type, reference_id, request_hash, user_id, status, reject_reason, created_at)
                        SELECT gen_random_uuid(), t.reference_type, gen_random_uuid(), repeat('0', 64), t.user_id,
                               CASE WHEN t.rejected THEN 'REJECTED' ELSE 'POSTED' END,
                               CASE WHEN t.rejected THEN 'INSUFFICIENT_BALANCE' END, t.created_at
                        FROM (
                            SELECT CASE WHEN i %% 10 < 4 THEN 'CHARGE' WHEN i %% 10 < 7 THEN 'PAYMENT'
                                        WHEN i %% 10 < 9 THEN 'TRANSFER' ELSE 'EXCHANGE' END AS reference_type,
                                   i %% 50 = 4 AS rejected,
                                   w.user_id,
                                   timestamptz '%s' + random() * interval '365 days' AS created_at
                            FROM generate_series(0, %d) i
                            JOIN perf_wallets w ON w.n = (i / 10) %% %d
                        ) t
                        ORDER BY t.created_at
                        """.formatted(YEAR_START, scale - 1, USERS));
                st.execute("""
                        INSERT INTO ledger_entries
                            (id, transaction_id, account_id, currency, entry_type, direction, amount,
                             balance_after, created_at)
                        SELECT gen_random_uuid(), t.id,
                               CASE s.acct WHEN 'USER_KRW' THEN w.krw WHEN 'USER_JPY' THEN w.jpy
                                           WHEN 'PEER_KRW' THEN p.krw
                                           WHEN 'CHARGE_SOURCE' THEN '%s'::uuid WHEN 'MERCHANT' THEN '%s'::uuid
                                           WHEN 'FX_KRW' THEN '%s'::uuid WHEN 'FX_JPY' THEN '%s'::uuid
                                           WHEN 'FEE_KRW' THEN '%s'::uuid END,
                               s.currency, s.entry_type, s.direction, s.amount, 0, t.created_at
                        FROM ledger_transactions t
                        JOIN perf_wallets w ON w.user_id = t.user_id
                        JOIN perf_wallets p ON p.n = (w.n + 1) %% %d
                        JOIN (VALUES
                            ('CHARGE', 1, 'CHARGE_SOURCE', 'KRW', 'CHARGE_OUT', 'DEBIT', 5000),
                            ('CHARGE', 2, 'USER_KRW', 'KRW', 'CHARGE_IN', 'CREDIT', 5000),
                            ('PAYMENT', 1, 'USER_KRW', 'KRW', 'PAYMENT_OUT', 'DEBIT', 3000),
                            ('PAYMENT', 2, 'MERCHANT', 'KRW', 'PAYMENT_IN', 'CREDIT', 3000),
                            ('TRANSFER', 1, 'USER_KRW', 'KRW', 'TRANSFER_OUT', 'DEBIT', 2000),
                            ('TRANSFER', 2, 'PEER_KRW', 'KRW', 'TRANSFER_IN', 'CREDIT', 2000),
                            ('EXCHANGE', 1, 'USER_KRW', 'KRW', 'EXCHANGE_OUT', 'DEBIT', 10000),
                            ('EXCHANGE', 2, 'FX_KRW', 'KRW', 'EXCHANGE_OUT', 'CREDIT', 10000),
                            ('EXCHANGE', 3, 'FX_JPY', 'JPY', 'EXCHANGE_IN', 'DEBIT', 1000),
                            ('EXCHANGE', 4, 'USER_JPY', 'JPY', 'EXCHANGE_IN', 'CREDIT', 1000),
                            ('EXCHANGE', 5, 'USER_KRW', 'KRW', 'EXCHANGE_FEE', 'DEBIT', 100),
                            ('EXCHANGE', 6, 'FEE_KRW', 'KRW', 'EXCHANGE_FEE', 'CREDIT', 100)
                        ) s (reference_type, seq, acct, currency, entry_type, direction, amount)
                            ON s.reference_type = t.reference_type
                        WHERE t.status = 'POSTED'
                        ORDER BY t.created_at, t.id, s.seq
                        """.formatted(chargeSource, merchant, fxKrw, fxJpy, feeKrw, activeUsers));
            }
            return null;
        });
    }

    /** 잔액 맞춤 전 확인: 계정별 POSTED 순합계가 음수인 USER 계정 (id=순합계) */
    private List<String> negativeUserAccounts() {
        return jdbcTemplate.queryForList("""
                SELECT a.id::text || '=' || s.net
                FROM (SELECT e.account_id, SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) AS net
                      FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                      WHERE t.status = 'POSTED' GROUP BY e.account_id) s
                JOIN ledger_accounts a ON a.id = s.account_id
                WHERE a.owner_type = 'USER' AND s.net < 0
                ORDER BY a.id
                """, String.class);
    }

    /** 측정 데이터 준비: 계정 잔액 = POSTED 항목 합 (생성 직후 모든 잔액은 0) */
    private void alignBalances() {
        jdbcTemplate.update("""
                UPDATE ledger_accounts a SET balance = s.net
                FROM (SELECT e.account_id, SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) AS net
                      FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                      WHERE t.status = 'POSTED' GROUP BY e.account_id) s
                WHERE a.id = s.account_id
                """);
        // autovacuum을 껐으므로 갱신으로 생긴 죽은 행을 직접 정리한다
        jdbcTemplate.execute("VACUUM ANALYZE ledger_accounts");
    }

    private void analyze() {
        jdbcTemplate.execute("ANALYZE ledger_transactions");
        jdbcTemplate.execute("ANALYZE ledger_entries");
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String size(String table) {
        return jdbcTemplate.queryForObject("SELECT pg_size_pretty(pg_total_relation_size(?::regclass))",
                String.class, table);
    }

    private static long checked(JsonNode data, String check) {
        return data.get(check).get("checkedCount").asLong();
    }

    // =====================================================================
    // 측정
    // =====================================================================

    private record Explain(String median, List<String> runs, String plan) {
    }

    private Explain explainMedian(String sql) {
        return jdbcTemplate.execute((ConnectionCallback<Explain>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("SET statement_timeout = " + TIMEOUT_SECONDS * 1_000L);
                List<Double> times = new ArrayList<>();
                List<String> runs = new ArrayList<>();
                String plan = null;
                for (int i = 0; i <= RUNS; i++) {
                    plan = plan(st, "EXPLAIN (ANALYZE, BUFFERS) " + sql);
                    if (i == 0) {
                        continue;
                    }
                    double ms = executionTime(plan);
                    times.add(ms);
                    runs.add(String.format("%.1f", ms));
                }
                st.execute("RESET statement_timeout");
                return new Explain(medianText(times), runs, plan);
            }
        });
    }

    private static String plan(Statement st, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static double executionTime(String plan) {
        return plan.lines().filter(l -> l.trim().startsWith("Execution Time:"))
                .map(l -> l.replaceAll("[^0-9.]", ""))
                .mapToDouble(Double::parseDouble).findFirst().orElseThrow();
    }

    /** 노드 이름만 남긴 계획 모양 */
    private static String shape(String plan) {
        return plan.lines()
                .filter(l -> l.contains("(cost=") || l.contains("(never executed)"))
                .map(l -> l.replaceFirst("\\s*\\(cost=.*", "").replace("->", "").trim())
                .collect(Collectors.joining(" > "));
    }

    /** Disk Usage·external sort·temp 버퍼 줄. 없으면 "없음" */
    private static String disk(String plan) {
        String lines = plan.lines().map(String::trim)
                .filter(l -> l.contains("Disk") || l.contains("temp read") || l.contains("temp written"))
                .distinct()
                .collect(Collectors.joining(" | "));
        return lines.isEmpty() ? "없음" : lines;
    }

    private record Api(String median, List<String> runs, List<Integer> statuses) {
    }

    private Api apiMedian() throws Exception {
        List<Double> times = new ArrayList<>();
        List<String> runs = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i <= RUNS; i++) {
            long start = System.nanoTime();
            HttpResponse<String> response = get();
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            if (i == 0) {
                continue;
            }
            statuses.add(response.statusCode());
            times.add(response.statusCode() == 200 ? ms : Double.POSITIVE_INFINITY);
            runs.add(String.format("%.1f", ms));
        }
        return new Api(medianText(times), runs, statuses);
    }

    private HttpResponse<String> get() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + INTEGRITY))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS + 30L)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String medianText(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        double median = sorted.get(sorted.size() / 2);
        return Double.isInfinite(median) ? "실패" : String.format("%.1f ms", median);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void line(String text) {
        report.append(text).append('\n');
    }
}
