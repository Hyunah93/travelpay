package com.travelpay.ledger;

import com.travelpay.ledger.infra.persistence.integrity.LedgerIntegritySql;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정합성 검사 2(POSTED 거래 통화별 차대)의 SQL 방식 비교 실험(#338). 운영 SQL·설정은 바꾸지 않고 결과만 기록한다.
 * <p>
 * 실행: {@code ./gradlew :ledger-service:performanceTest --tests 'com.travelpay.ledger.LedgerIntegrityQueryExperimentTest'}
 * (기본 test에서 빠짐. 120만 거래 생성 포함 약 4분, 결과는 표준 출력의 {@code ===== PERF REPORT =====} 구간)
 * <p>
 * 데이터 생성·잔액 맞춤·ANALYZE는 {@link LedgerIntegrityCheckPerformanceTest}의 코드를 그대로 복사해 같은 데이터로 비교한다.
 * 이 클래스는 자기 Testcontainers 컨테이너만 쓴다. 비교 방식은 다음과 같다:
 * A 현재 SQL / B 항목 먼저 집계 후 POSTED 조인 / C transaction_id 정렬 입력 집계 /
 * D A + 트랜잭션 안에서만 SET LOCAL work_mem / 참고 A + SET LOCAL 병렬 비용 0.
 * 방식을 바퀴마다 교차(시작 방식 회전)해 6바퀴 돌리고 첫 바퀴를 버리며, 조작 데이터(롤백)로 결과가 A와 같은지 확인한다.
 * <p>
 * 결론(120만 거래, 2026-11-15 측정): <b>A 유지</b>.
 * <ul>
 *     <li>B·C는 결과는 같지만 플래너가 인덱스 순서 집계(Index Scan → Incremental Sort → GroupAggregate)를 골라
 *         힙을 무작위로 읽어 A(약 2.0초)보다 약 55% 느리다(약 3.1초). 병렬 집계(Partial/Finalize Aggregate)는 나오지 않는다.
 *         (거래, 통화) 묶음 수가 입력 행의 절반 가까이 돼 부분 집계로 줄어드는 양이 거의 없기 때문이다</li>
 *     <li>D(work_mem 192MB, 해시 집계 약 312MB)는 디스크를 없애도 약 6% 빨라질 뿐이다. 같은 A의 측정 간 차이(약 7%, 1,902ms ↔ 2,041ms)보다 작아 개선으로 보지 않는다</li>
 *     <li>병렬 비용 힌트는 약 34% 빠르지만 SQL 형태가 아닌 설정 힌트이고, 워커 2개와 temp 약 470MB를 쓴다</li>
 * </ul>
 */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service"
})
class LedgerIntegrityQueryExperimentTest {

    private static final String SCHEMA = "ledger_service";
    private static final int TIMEOUT_SECONDS = 600;
    private static final int VIOLATION_LIMIT = 100;
    private static final int USERS = 10_000;
    private static final String YEAR_START = "2025-01-01T00:00:00Z";

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final StringBuilder report = new StringBuilder();

    // =====================================================================
    // 실험: 검사 2(거래 차대) 방식 비교. 운영 SQL·설정은 바꾸지 않는다
    // =====================================================================

    private static final int EXPERIMENT_SCALE = 1_200_000;
    private static final int EXPERIMENT_ROUNDS = 6;
    /** D 후보(작은 값부터). 해시 집계에 디스크가 생기지 않는 가장 작은 값을 고른다 */
    private static final List<String> WORK_MEM_CANDIDATES = List.of("128MB", "192MB", "256MB");

    /** 검사 2 공통 끝부분: 검사 대상 수, 전체 위반 수, 앞쪽 N건 (운영 SQL과 같음) */
    private static final String CHECK2_TAIL = """
            SELECT (SELECT count(*) FROM ledger_transactions WHERE status = 'POSTED') AS checked_count,
                   (SELECT count(*) FROM violations) AS violation_count,
                   v.transaction_id, v.currency, v.debit_sum, v.credit_sum
            FROM (SELECT 1) AS one
            LEFT JOIN LATERAL (
                SELECT * FROM violations ORDER BY transaction_id, currency LIMIT ?
            ) AS v ON true
            """;

    /** B: 항목만 먼저 (거래, 통화)별로 집계하고 부호 합계가 0이 아닌 것만 남긴 뒤 POSTED 거래와 조인 */
    private static final String CHECK2_B = """
            WITH violations AS (
                SELECT g.transaction_id, g.currency, g.debit_sum, g.credit_sum
                FROM (
                    SELECT e.transaction_id, e.currency,
                           SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END) AS debit_sum,
                           SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END) AS credit_sum
                    FROM ledger_entries e
                    GROUP BY e.transaction_id, e.currency
                    HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0
                ) g
                JOIN ledger_transactions t ON t.id = g.transaction_id AND t.status = 'POSTED'
            )
            """ + CHECK2_TAIL;

    /** C: transaction_id 순으로 정렬된 항목을 입력으로 (거래, 통화)별 집계 → POSTED 거래와 조인 */
    private static final String CHECK2_C = """
            WITH violations AS (
                SELECT g.transaction_id, g.currency, g.debit_sum, g.credit_sum
                FROM (
                    SELECT o.transaction_id, o.currency,
                           SUM(CASE WHEN o.direction = 'DEBIT' THEN o.amount ELSE 0 END) AS debit_sum,
                           SUM(CASE WHEN o.direction = 'CREDIT' THEN o.amount ELSE 0 END) AS credit_sum
                    FROM (
                        SELECT e.transaction_id, e.currency, e.direction, e.amount
                        FROM ledger_entries e
                        ORDER BY e.transaction_id
                    ) o
                    GROUP BY o.transaction_id, o.currency
                    HAVING SUM(CASE WHEN o.direction = 'DEBIT' THEN o.amount ELSE 0 END)
                           <> SUM(CASE WHEN o.direction = 'CREDIT' THEN o.amount ELSE 0 END)
                ) g
                JOIN ledger_transactions t ON t.id = g.transaction_id AND t.status = 'POSTED'
            )
            """ + CHECK2_TAIL;

    /** 방식 이름, SQL, 그 트랜잭션 안에서만 적용할 SET LOCAL 문 */
    private record Variant(String name, String sql, List<String> setLocals) {
    }

    @Test
    void 검사2_방식_비교_실험() throws Exception {
        line("## 실험: 검사 2 방식 비교 (" + EXPERIMENT_SCALE + ")");
        for (String setting : List.of("work_mem", "hash_mem_multiplier", "jit", "max_parallel_workers_per_gather",
                "parallel_setup_cost", "parallel_tuple_cost", "autovacuum")) {
            line(setting + " = " + jdbcTemplate.queryForObject("show " + setting, String.class));
        }
        createAccounts();
        long start = System.nanoTime();
        generate(EXPERIMENT_SCALE);
        List<String> negatives = negativeUserAccounts();
        assertTrue(negatives.isEmpty(), negatives.toString());
        alignBalances();
        analyze();
        line("준비(생성 + 음수 확인 + 잔액 맞춤 + ANALYZE, 기존 단계 그대로) " + elapsedMs(start) + " ms, 거래 "
                + count("SELECT count(*) FROM ledger_transactions") + " / 항목 " + count("SELECT count(*) FROM ledger_entries"));
        line("ANALYZE 시각: entries=" + jdbcTemplate.queryForObject(
                "SELECT last_analyze::text FROM pg_stat_user_tables WHERE relname = 'ledger_entries'", String.class)
                + ", transactions=" + jdbcTemplate.queryForObject(
                "SELECT last_analyze::text FROM pg_stat_user_tables WHERE relname = 'ledger_transactions'", String.class));

        String workMem = chooseWorkMem();
        List<Variant> variants = List.of(
                new Variant("A 현재", LedgerIntegritySql.POSTED_TRANSACTION_BALANCES, List.of()),
                new Variant("B 집계 먼저", CHECK2_B, List.of()),
                new Variant("C 정렬 입력", CHECK2_C, List.of()),
                new Variant("D A+work_mem " + workMem, LedgerIntegritySql.POSTED_TRANSACTION_BALANCES,
                        List.of("SET LOCAL work_mem = '" + workMem + "'")),
                new Variant("참고 A+병렬비용0", LedgerIntegritySql.POSTED_TRANSACTION_BALANCES,
                        List.of("SET LOCAL parallel_setup_cost = 0", "SET LOCAL parallel_tuple_cost = 0")));

        compareResults(variants);
        measureRounds(variants);
        System.out.println("\n===== PERF REPORT =====\n" + report + "===== END PERF REPORT =====");
    }

    /** 후보를 작은 값부터 한 번씩 실행해 해시 집계 디스크가 없어지는 가장 작은 값 */
    private String chooseWorkMem() {
        line("");
        line("### D work_mem 후보 (한 번씩, 트랜잭션 안에서만)");
        String chosen = null;
        for (String candidate : WORK_MEM_CANDIDATES) {
            Run run = explainInTransaction(LedgerIntegritySql.POSTED_TRANSACTION_BALANCES,
                    List.of("SET LOCAL work_mem = '" + candidate + "'"));
            String aggregateDisk = run.plan().lines().map(String::trim)
                    .filter(l -> l.startsWith("Planned Partitions") || l.startsWith("Batches"))
                    .collect(Collectors.joining(" | "));
            line("- " + candidate + ": " + String.format("%.1f ms", run.ms()) + ", " + aggregateDisk
                    + ", 디스크: " + disk(run.plan()));
            if (chosen == null && !run.plan().contains("Disk Usage")) {
                chosen = candidate;
            }
        }
        return chosen == null ? WORK_MEM_CANDIDATES.getLast() : chosen;
    }

    // ---- 결과 동일 여부 ----

    /**
     * 정상 데이터와 조작 데이터에서 방식마다 실제 결과(검사 대상 수, 위반 수, 앞쪽 N건)가 A와 같은지 본다.
     * 조작은 LedgerIntegrityTamperIntegrationTest와 같은 다섯 가지 + 위반 150건(LIMIT 100 잘림)이며,
     * 조작마다 트랜잭션 안에서 하고 롤백한다. 방식마다 SAVEPOINT로 감싸 SET LOCAL이 다음 방식에 남지 않게 한다.
     */
    private void compareResults(List<Variant> variants) {
        line("");
        line("### 결과 동일 여부 (실제 실행, 조작은 롤백)");
        String[] picked = jdbcTemplate.queryForObject("""
                SELECT (SELECT id::text FROM ledger_transactions WHERE status = 'POSTED' AND reference_type = 'TRANSFER'
                        ORDER BY id LIMIT 1),
                       (SELECT id::text FROM ledger_transactions WHERE status = 'POSTED' AND reference_type = 'CHARGE'
                        ORDER BY id LIMIT 1),
                       (SELECT id::text FROM ledger_transactions WHERE status = 'REJECTED' ORDER BY id LIMIT 1)
                """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
        String transfer = picked[0];
        String charge = picked[1];
        String rejected = picked[2];
        Map<String, List<String>> tampers = new LinkedHashMap<>();
        tampers.put("정상", List.of());
        tampers.put("SYSTEM 잔액 +1 (기대: 검사 2 위반 0)", List.of(
                "UPDATE ledger_accounts SET balance = balance + 1 WHERE id = '" + chargeSource + "'"));
        tampers.put("POSTED 항목 금액 변경 (기대: 그 거래 1건)", List.of("""
                UPDATE ledger_entries SET amount = amount + 1
                WHERE id = (SELECT id FROM ledger_entries WHERE transaction_id = '%s' AND direction = 'DEBIT' LIMIT 1)
                """.formatted(transfer)));
        tampers.put("POSTED 항목 방향 반전 (기대: 그 거래 1건)", List.of("""
                UPDATE ledger_entries SET direction = 'DEBIT'
                WHERE id = (SELECT id FROM ledger_entries WHERE transaction_id = '%s' AND direction = 'CREDIT' LIMIT 1)
                """.formatted(charge)));
        tampers.put("REJECTED 거래에 항목 추가 (기대: 검사 2 위반 0)", List.of("""
                INSERT INTO ledger_entries (id, transaction_id, account_id, currency, entry_type, direction, amount,
                                            balance_after)
                VALUES (gen_random_uuid(), '%s', '%s', 'KRW', 'CHARGE_IN', 'CREDIT', 100, 0)
                """.formatted(rejected, chargeSource)));
        tampers.put("POSTED → REJECTED (기대: 검사 2 위반 0)", List.of("""
                UPDATE ledger_transactions SET status = 'REJECTED', reject_reason = 'CANCELLED_BY_REQUESTER'
                WHERE id = '%s'
                """.formatted(transfer)));
        tampers.put("POSTED 거래 150건 금액 변경 (기대: 150건, 앞 100건 반환)", List.of("""
                UPDATE ledger_entries SET amount = amount + 1
                WHERE id IN (SELECT DISTINCT ON (transaction_id) e.id
                             FROM ledger_entries e
                             WHERE e.transaction_id IN (SELECT id FROM ledger_transactions
                                                        WHERE status = 'POSTED' ORDER BY created_at LIMIT 150)
                             ORDER BY transaction_id, e.id)
                """));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("정상", "0");
        expected.put("SYSTEM 잔액 +1 (기대: 검사 2 위반 0)", "0");
        expected.put("POSTED 항목 금액 변경 (기대: 그 거래 1건)", "1:" + transfer);
        expected.put("POSTED 항목 방향 반전 (기대: 그 거래 1건)", "1:" + charge);
        expected.put("REJECTED 거래에 항목 추가 (기대: 검사 2 위반 0)", "0");
        expected.put("POSTED → REJECTED (기대: 검사 2 위반 0)", "0");
        expected.put("POSTED 거래 150건 금액 변경 (기대: 150건, 앞 100건 반환)", "150");

        for (Map.Entry<String, List<String>> tamper : tampers.entrySet()) {
            Map<String, String> results = jdbcTemplate.execute((ConnectionCallback<Map<String, String>>) connection -> {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (Statement st = connection.createStatement()) {
                    st.execute("SET LOCAL statement_timeout = " + TIMEOUT_SECONDS * 1_000L);
                    for (String sql : tamper.getValue()) {
                        st.execute(sql);
                    }
                    Map<String, String> byVariant = new LinkedHashMap<>();
                    for (Variant variant : variants) {
                        st.execute("SAVEPOINT variant");
                        for (String setLocal : variant.setLocals()) {
                            st.execute(setLocal);
                        }
                        byVariant.put(variant.name(), resultSignature(connection, variant.sql()));
                        st.execute("ROLLBACK TO SAVEPOINT variant");
                    }
                    byVariant.put("_work_mem_after", queryString(st, "SHOW work_mem"));
                    return byVariant;
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                }
            });
            String reference = results.get(variants.getFirst().name());
            String expect = expected.get(tamper.getKey());
            boolean expectedOk = expect.contains(":")
                    ? reference.startsWith("violations=" + expect.substring(0, expect.indexOf(':')) + " ")
                    && reference.contains(expect.substring(expect.indexOf(':') + 1))
                    : reference.startsWith("violations=" + expect + " ");
            StringBuilder row = new StringBuilder("- " + tamper.getKey() + ": A 기대 일치=" + expectedOk);
            for (Variant variant : variants) {
                row.append(", ").append(variant.name()).append(" A와 동일=")
                        .append(reference.equals(results.get(variant.name())));
            }
            row.append(", 롤백 뒤 SAVEPOINT 밖 work_mem=").append(results.get("_work_mem_after"));
            row.append(" | A: ").append(reference.length() > 160 ? reference.substring(0, 160) + "…" : reference);
            line(row.toString());
        }
        assertEquals("4MB", jdbcTemplate.queryForObject("SHOW work_mem", String.class), "SET LOCAL이 남음");
    }

    /** 실제 결과 요약: 검사 대상 수, 위반 수, 반환 행(거래·통화·합계) 전체의 해시와 처음 몇 건 */
    private static String resultSignature(java.sql.Connection connection, String sql) throws SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, VIOLATION_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                long checked = -1;
                long violations = -1;
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    checked = rs.getLong("checked_count");
                    violations = rs.getLong("violation_count");
                    if (rs.getObject("transaction_id") != null) {
                        rows.add(rs.getString("transaction_id") + "/" + rs.getString("currency").trim() + "/"
                                + rs.getBigDecimal("debit_sum") + "/" + rs.getBigDecimal("credit_sum"));
                    }
                }
                return "violations=" + violations + " checked=" + checked + " returned=" + rows.size()
                        + " rowsHash=" + rows.hashCode() + " first=" + rows.stream().limit(2).toList();
            }
        }
    }

    // ---- 시간 측정 ----

    private record Run(double ms, String plan) {
    }

    /** 한 트랜잭션에서 SET LOCAL → EXPLAIN (ANALYZE, BUFFERS) → 롤백. 설정은 그 트랜잭션에만 적용된다 */
    private Run explainInTransaction(String sql, List<String> setLocals) {
        return jdbcTemplate.execute((ConnectionCallback<Run>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.execute("SET LOCAL statement_timeout = " + TIMEOUT_SECONDS * 1_000L);
                for (String setLocal : setLocals) {
                    st.execute(setLocal);
                }
                String plan = plan(st, "EXPLAIN (ANALYZE, BUFFERS) "
                        + sql.replace("LIMIT ?", "LIMIT " + VIOLATION_LIMIT));
                return new Run(executionTime(plan), plan);
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /**
     * 바퀴마다 방식을 한 번씩 교차 실행한다. 시작 방식을 바퀴마다 한 칸씩 돌려 순서 효과도 섞는다. 첫 바퀴는 버린다.
     * 바퀴별 시간·shared hit/read·temp와 마지막 실행 계획 요약을 남긴다.
     */
    private void measureRounds(List<Variant> variants) {
        Map<String, List<Double>> times = new LinkedHashMap<>();
        Map<String, List<String>> runs = new LinkedHashMap<>();
        Map<String, String> lastPlan = new LinkedHashMap<>();
        variants.forEach(v -> {
            times.put(v.name(), new ArrayList<>());
            runs.put(v.name(), new ArrayList<>());
        });
        line("");
        line("### 바퀴별 실행 (" + EXPERIMENT_ROUNDS + "바퀴, 첫 바퀴 제외, 시작 방식 회전)");
        for (int round = 0; round < EXPERIMENT_ROUNDS; round++) {
            List<String> order = new ArrayList<>();
            for (int k = 0; k < variants.size(); k++) {
                Variant variant = variants.get((round + k) % variants.size());
                Run run = explainInTransaction(variant.sql(), variant.setLocals());
                order.add(variant.name().substring(0, 1).equals("참") ? "참고" : variant.name().substring(0, 1));
                String buffers = topBuffers(run.plan());
                runs.get(variant.name()).add((round == 0 ? "(버림)" : "") + String.format("%.0f", run.ms())
                        + "ms[" + buffers + "]");
                if (round > 0) {
                    times.get(variant.name()).add(run.ms());
                }
                lastPlan.put(variant.name(), run.plan());
            }
            line("- 바퀴 " + (round + 1) + " 순서: " + String.join("→", order));
        }
        line("");
        line("### 방식별 요약");
        for (Variant variant : variants) {
            String plan = lastPlan.get(variant.name());
            line("- " + variant.name() + ": 중앙값 " + medianText(times.get(variant.name())));
            line("  - 바퀴별: " + runs.get(variant.name()));
            line("  - 계획(CTE violations): " + violationsShape(plan));
            line("  - 병렬: " + parallelInfo(plan) + " / 사용 인덱스: " + indexes(plan));
            line("  - Sort: " + matching(plan, "Sort Method", "Presorted Key", "Full-sort Groups")
                    + " / Aggregate: " + aggregates(plan));
            line("  - 디스크: " + disk(plan));
            System.out.println("---- 실험 전체 계획: " + variant.name() + "\n" + plan);
        }
    }

    /** 맨 위 노드의 Buffers 줄(shared hit/read, temp) */
    private static String topBuffers(String plan) {
        return plan.lines().map(String::trim).filter(l -> l.startsWith("Buffers:")).findFirst()
                .orElse("").replace("Buffers: ", "");
    }

    /** CTE violations 아래 노드 모양만 (InitPlan 전까지) */
    private static String violationsShape(String plan) {
        StringBuilder shape = new StringBuilder();
        boolean inCte = false;
        for (String line : plan.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("CTE violations")) {
                inCte = true;
                continue;
            }
            if (inCte && (trimmed.startsWith("InitPlan") || trimmed.startsWith("->  Result"))) {
                break;
            }
            if (inCte && (line.contains("(cost=") || line.contains("(never executed)"))) {
                if (!shape.isEmpty()) {
                    shape.append(" > ");
                }
                shape.append(line.replaceFirst("\\s*\\(cost=.*", "").replace("->", "").trim());
            }
        }
        return shape.toString();
    }

    private static String parallelInfo(String plan) {
        String cte = cteSection(plan);
        boolean gather = cte.contains("Gather");
        boolean partial = cte.contains("Partial ") && cte.contains("Aggregate");
        String launched = cte.lines().map(String::trim).filter(l -> l.startsWith("Workers Launched"))
                .collect(Collectors.joining(", "));
        return "Gather=" + gather + ", Partial/Finalize 집계=" + partial + (launched.isEmpty() ? "" : ", " + launched);
    }

    private static String cteSection(String plan) {
        int start = plan.indexOf("CTE violations");
        int end = plan.indexOf("InitPlan", Math.max(start, 0));
        return start < 0 ? "" : plan.substring(start, end < 0 ? plan.length() : end);
    }

    private static String indexes(String plan) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("using (\\w+) on (\\w+)").matcher(plan);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found.isEmpty() ? "없음" : found.stream().distinct().collect(Collectors.joining(", "));
    }

    private static String aggregates(String plan) {
        return cteSection(plan).lines()
                .filter(l -> l.contains("Aggregate") && (l.contains("(cost=") || l.contains("(never executed)")))
                .map(l -> l.replaceFirst("\\s*\\(cost=.*", "").replace("->", "").trim())
                .distinct().collect(Collectors.joining(", "));
    }

    private static String matching(String plan, String... prefixes) {
        String found = cteSection(plan).lines().map(String::trim)
                .filter(l -> java.util.Arrays.stream(prefixes).anyMatch(l::startsWith))
                .distinct().collect(Collectors.joining(" | "));
        return found.isEmpty() ? "없음" : found;
    }

    private static String queryString(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    // =====================================================================
    // 데이터 생성: LedgerIntegrityCheckPerformanceTest에서 그대로 복사 (같은 데이터로 비교하려고 바꾸지 않음)
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

    // =====================================================================
    // 측정 도구: LedgerIntegrityCheckPerformanceTest와 같음
    // =====================================================================

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

    /** Disk Usage·external sort·temp 버퍼 줄. 없으면 "없음" */
    private static String disk(String plan) {
        String lines = plan.lines().map(String::trim)
                .filter(l -> l.contains("Disk") || l.contains("temp read") || l.contains("temp written"))
                .distinct()
                .collect(Collectors.joining(" | "));
        return lines.isEmpty() ? "없음" : lines;
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
