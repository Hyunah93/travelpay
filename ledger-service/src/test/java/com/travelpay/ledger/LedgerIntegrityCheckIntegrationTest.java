package com.travelpay.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.travelpay.ledger.infra.persistence.integrity.LedgerIntegrityRepositoryImpl.LOCK_KEY_INTEGRITY_CHECK;
import static com.travelpay.ledger.infra.persistence.integrity.LedgerIntegrityRepositoryImpl.LOCK_KEY_LEDGER;
import static com.travelpay.ledger.LedgerTestClient.body;
import static com.travelpay.ledger.LedgerTestClient.cancelBody;
import static com.travelpay.ledger.LedgerTestClient.system;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static com.travelpay.ledger.LedgerTestClient.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 정합성 검사 API(GET /internal/v1/ledger/integrity)를 정상 데이터·동시 호출·timeout으로 확인한다(데이터 조작 없음).
 * <ul>
 *     <li>API로 만든 충전·송금·환전·취소·잔액 부족 거절 뒤 위반 0, 검사 대상 수는 DB 행 수와 같다</li>
 *     <li>읽기 전용 트랜잭션 안에서 pg_try_advisory_xact_lock(int, int)이 동작하고 커밋 뒤 풀린다</li>
 *     <li>검사가 진행 중(풀 밖 연결이 ledger_entries를 잠가 멈춰 둠)이면 다른 요청은 기다리지 않고 409</li>
 *     <li>검사 timeout(이 클래스는 2초)을 넘기면 503이고, 잠금이 풀려 다음 검사가 정상 실행된다</li>
 * </ul>
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "travelpay.ledger.integrity.timeout-seconds=" + LedgerIntegrityCheckIntegrationTest.TIMEOUT_SECONDS
})
class LedgerIntegrityCheckIntegrationTest {

    static final int TIMEOUT_SECONDS = 2;
    private static final String SCHEMA = "ledger_service";
    private static final String INTEGRITY = "/internal/v1/ledger/integrity";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String ADVISORY_HELD = """
            SELECT count(*) FROM pg_locks
            WHERE locktype = 'advisory' AND classid::bigint = ? AND objid::bigint = ? AND objsubid = 2 AND granted
            """;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private LedgerTestClient client;

    @BeforeEach
    void setUp() {
        client = new LedgerTestClient(port, jsonMapper);
    }

    // ---- 정상 데이터 ----

    @Test
    void API로_만든_정상_거래는_위반이_0이고_검사_대상_수는_DB_행_수와_같다() throws Exception {
        UUID walletA = client.openWallet();
        UUID walletB = client.openWallet();
        client.charge(walletA, 50_000);
        assertEquals(201, client.register(transferBody(UUID.randomUUID(), walletA, walletB, 7_000)).statusCode());
        assertEquals(201, client.register(exchangeBody(UUID.randomUUID(), walletA, 10_000, 1_000, 100))
                .statusCode());
        assertEquals(201, client.cancel(cancelBody("PAYMENT", UUID.randomUUID())).statusCode());
        // 잔액 부족 거절도 REJECTED 거래(항목 없음)로 남는다
        assertEquals(422, client.register(transferBody(UUID.randomUUID(), walletB, walletA, 1_000_000))
                .statusCode());

        HttpResponse<String> response = client.get(INTEGRITY);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertTrue(data.get("consistent").asBoolean(), response.body());
        assertEquals(0, data.get("totalViolationCount").asLong(), response.body());
        assertTrue(data.hasNonNull("checkedAt"), response.body());
        for (String check : List.of("accountBalance", "postedTransactionBalance", "rejectedTransactionEntries",
                "currencyTotal")) {
            JsonNode node = data.get(check);
            assertEquals(0, node.get("violationCount").asLong(), check + " " + response.body());
            assertEquals(0, node.get("returnedCount").asInt(), check);
            assertFalse(node.get("truncated").asBoolean(), check);
            assertTrue(node.get("violations").isEmpty(), check);
        }
        long accounts = count("SELECT count(*) FROM ledger_accounts");
        assertEquals(accounts, data.get("accountBalance").get("checkedCount").asLong());
        assertEquals(accounts, data.get("currencyTotal").get("checkedCount").asLong());
        assertEquals(count("SELECT count(*) FROM ledger_transactions WHERE status = 'POSTED'"),
                data.get("postedTransactionBalance").get("checkedCount").asLong());
        long rejected = count("SELECT count(*) FROM ledger_transactions WHERE status = 'REJECTED'");
        assertTrue(rejected >= 2, "취소·잔액 부족 거절이 REJECTED로 남아야 함: " + rejected);
        assertEquals(rejected, data.get("rejectedTransactionEntries").get("checkedCount").asLong());
    }

    // ---- 읽기 전용 트랜잭션의 advisory lock ----

    @Test
    void 읽기_전용_트랜잭션에서_트랜잭션_advisory_lock이_잡히고_커밋_뒤_풀린다() throws Exception {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        try (Connection other = openOutsidePool()) {
            Boolean[] inside = readOnly.execute(status -> new Boolean[]{
                    "on".equals(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class)),
                    jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                            LOCK_KEY_LEDGER, LOCK_KEY_INTEGRITY_CHECK),
                    count(ADVISORY_HELD, LOCK_KEY_LEDGER, LOCK_KEY_INTEGRITY_CHECK) == 1,
                    tryLock(other)
            });

            assertTrue(inside[0], "읽기 전용 트랜잭션이어야 함");
            assertTrue(inside[1], "읽기 전용 트랜잭션에서 pg_try_advisory_xact_lock이 true여야 함");
            assertTrue(inside[2], "pg_locks에 정수 두 개 형태(objsubid=2)로 보여야 함");
            assertFalse(inside[3], "잡혀 있는 동안 다른 연결은 못 잡아야 함");
            // 커밋으로 풀렸으므로 다른 연결이 잡을 수 있다
            assertTrue(tryLock(other), "커밋 뒤에는 다른 연결이 잡을 수 있어야 함");
            other.rollback();
        }
    }

    // ---- 동시 호출 ----

    @Test
    void 검사가_진행_중이면_다른_요청은_기다리지_않고_409이고_먼저_요청은_정상_완료된다() throws Exception {
        CompletableFuture<HttpResponse<String>> first;
        HttpResponse<String> second;
        long secondElapsedMs;
        try (Connection holder = openOutsidePool()) {
            lockEntries(holder);
            first = client.getAsync(INTEGRITY);
            awaitUntil(() -> count(ADVISORY_HELD, LOCK_KEY_LEDGER, LOCK_KEY_INTEGRITY_CHECK) == 1
                            && waitingOnRelationLock(),
                    "먼저 요청이 검사 잠금을 잡고 ledger_entries 잠금을 기다리는 상태");

            long start = System.nanoTime();
            second = client.get(INTEGRITY);
            secondElapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertFalse(first.isDone(), "두 번째 요청 동안 먼저 요청은 아직 진행 중이어야 함");
            holder.rollback();
        }

        assertEquals(409, second.statusCode(), second.body());
        assertEquals("LEDGER_INTEGRITY_CHECK_IN_PROGRESS", client.errorCode(second.body()));
        assertTrue(secondElapsedMs < 1_000, "기다리지 않고 바로 응답해야 함: " + secondElapsedMs + "ms");
        HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode(), firstResponse.body());
        assertTrue(client.data(firstResponse).get("consistent").asBoolean(), firstResponse.body());
    }

    // ---- timeout ----

    @Test
    void 검사_timeout을_넘기면_503이고_잠금이_풀려_다음_검사는_정상_실행된다() throws Exception {
        HttpResponse<String> timedOut;
        long elapsedMs;
        try (Connection holder = openOutsidePool()) {
            lockEntries(holder);
            long start = System.nanoTime();
            timedOut = client.get(INTEGRITY);
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // 앱 트랜잭션이 롤백됐으므로 ledger_entries 잠금을 쥔 채로도 검사 잠금은 이미 풀려 있다
            assertEquals(0, count(ADVISORY_HELD, LOCK_KEY_LEDGER, LOCK_KEY_INTEGRITY_CHECK),
                    "timeout 뒤 검사 잠금이 남아 있음");
            holder.rollback();
        }

        assertEquals(503, timedOut.statusCode(), timedOut.body());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", client.errorCode(timedOut.body()));
        assertTrue(elapsedMs >= TIMEOUT_SECONDS * 1_000L - 500 && elapsedMs < TIMEOUT_SECONDS * 1_000L + 3_000,
                "설정한 timeout 근처에서 끝나야 함: " + elapsedMs + "ms");

        HttpResponse<String> next = client.get(INTEGRITY);
        assertEquals(200, next.statusCode(), next.body());
        assertTrue(client.data(next).get("consistent").asBoolean(), next.body());
    }

    // ---- 도구 ----

    /** 환전 본문: 지갑 KRW → JPY. 회사 쪽은 FX_POSITION 두 통화, 수수료는 FEE_REVENUE KRW */
    static Map<String, Object> exchangeBody(UUID referenceId, UUID walletId, long krw, long jpy, long fee) {
        return body("EXCHANGE", referenceId, List.of(
                user(walletId, "KRW", "DEBIT", krw, "EXCHANGE_OUT"),
                system("FX_POSITION", "KRW", "CREDIT", krw, "EXCHANGE_OUT"),
                system("FX_POSITION", "JPY", "DEBIT", jpy, "EXCHANGE_IN"),
                user(walletId, "JPY", "CREDIT", jpy, "EXCHANGE_IN"),
                user(walletId, "KRW", "DEBIT", fee, "EXCHANGE_FEE"),
                system("FEE_REVENUE", "KRW", "CREDIT", fee, "EXCHANGE_FEE")));
    }

    private Connection openOutsidePool() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    /** 검사 SQL이 읽는 ledger_entries를 트랜잭션이 끝날 때까지 막는다 */
    private static void lockEntries(Connection holder) throws SQLException {
        try (Statement statement = holder.createStatement()) {
            statement.execute("LOCK TABLE ledger_entries IN ACCESS EXCLUSIVE MODE");
        }
    }

    private static boolean tryLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
            statement.setInt(1, LOCK_KEY_LEDGER);
            statement.setInt(2, LOCK_KEY_INTEGRITY_CHECK);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean waitingOnRelationLock() {
        return count("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND wait_event = 'relation' AND query ILIKE '%ledger_entries%'
                """) >= 1;
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private static void awaitUntil(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(WAIT_TIMEOUT.toSeconds() + "초 안에 도달하지 못함: " + description);
    }
}
