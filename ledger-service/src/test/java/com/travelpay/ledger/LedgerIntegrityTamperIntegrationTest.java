package com.travelpay.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.travelpay.ledger.LedgerIntegrityCheckIntegrationTest.exchangeBody;
import static com.travelpay.ledger.LedgerTestClient.cancelBody;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 네이티브 SQL로 원장을 일부러 어긋나게 만든 뒤 정합성 검사가 기대한 검사에서만, 조작한 계정·거래를 찾는지 확인한다.
 * <ul>
 *     <li>테스트마다 거래·항목을 비우고 계정 잔액을 0으로 돌린 뒤 API로 정상 데이터를 새로 만든다(조작끼리 오염 없음).
 *         조작 전 검사가 위반 0인지 먼저 확인한다</li>
 *     <li>걸린 검사 집합이 기대와 정확히 같아야 하고, 위반 목록에 조작한 계정·거래 id가 있어야 한다</li>
 *     <li>위반 최대 건수를 2로 줄여 잘림(truncated)도 확인한다</li>
 * </ul>
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "travelpay.ledger.integrity.violation-limit=2"
})
class LedgerIntegrityTamperIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String INTEGRITY = "/internal/v1/ledger/integrity";
    private static final String ACCOUNT_BALANCE = "accountBalance";
    private static final String POSTED_BALANCE = "postedTransactionBalance";
    private static final String REJECTED_ENTRIES = "rejectedTransactionEntries";
    private static final String CURRENCY_TOTAL = "currencyTotal";
    private static final List<String> CHECKS = List.of(ACCOUNT_BALANCE, POSTED_BALANCE, REJECTED_ENTRIES,
            CURRENCY_TOTAL);

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

    private LedgerTestClient client;
    private UUID walletA;
    private UUID walletB;
    private UUID chargeTx;
    private UUID transferTx;
    private UUID cancelledTx;

    /** 비우고 정상 데이터(충전·송금·환전·취소)를 새로 만든 뒤 위반 0을 확인한다 */
    @BeforeEach
    void setUp() throws Exception {
        client = new LedgerTestClient(port, jsonMapper);
        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions");
        jdbcTemplate.update("UPDATE ledger_accounts SET balance = 0");

        walletA = client.openWallet();
        walletB = client.openWallet();
        chargeTx = client.charge(walletA, 50_000);
        transferTx = registered(transferBody(UUID.randomUUID(), walletA, walletB, 7_000));
        registered(exchangeBody(UUID.randomUUID(), walletA, 10_000, 1_000, 100));
        HttpResponse<String> cancelled = client.cancel(cancelBody("PAYMENT", UUID.randomUUID()));
        assertEquals(201, cancelled.statusCode(), cancelled.body());
        cancelledTx = UUID.fromString(client.data(cancelled).get("transactionId").asString());

        JsonNode before = check();
        assertEquals(Set.of(), violatedChecks(before), "조작 전에는 위반이 없어야 함: " + before);
    }

    @Test
    void SYSTEM_잔액_1_증가는_잔액_검사와_통화별_합계_검사에_걸린다() {
        UUID chargeSource = systemAccount("CHARGE_SOURCE", "KRW");
        jdbcTemplate.update("UPDATE ledger_accounts SET balance = balance + 1 WHERE id = ?", chargeSource);

        JsonNode data = check();

        // 잔액만 바꾸면 통화별 잔액 합도 1만큼 어긋나므로 4(요약)도 함께 걸린다
        assertEquals(Set.of(ACCOUNT_BALANCE, CURRENCY_TOTAL), violatedChecks(data), data.toString());
        assertEquals(List.of(chargeSource.toString()), ids(data, ACCOUNT_BALANCE, "accountId"));
        assertEquals(List.of("KRW"), ids(data, CURRENCY_TOTAL, "currency"));
        assertEquals(1, data.get(CURRENCY_TOTAL).get("violations").get(0).get("totalBalance").asLong());
    }

    @Test
    void POSTED_항목_금액_변경은_잔액_검사와_차대_검사에_걸린다() {
        Entry entry = entry(transferTx, "DEBIT");
        jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", entry.id());

        JsonNode data = check();

        assertEquals(Set.of(ACCOUNT_BALANCE, POSTED_BALANCE), violatedChecks(data), data.toString());
        assertEquals(List.of(entry.accountId().toString()), ids(data, ACCOUNT_BALANCE, "accountId"));
        assertEquals(List.of(transferTx.toString()), ids(data, POSTED_BALANCE, "transactionId"));
    }

    @Test
    void POSTED_항목_방향_반전은_잔액_검사와_차대_검사에_걸린다() {
        Entry entry = entry(chargeTx, "CREDIT");
        jdbcTemplate.update("UPDATE ledger_entries SET direction = 'DEBIT' WHERE id = ?", entry.id());

        JsonNode data = check();

        assertEquals(Set.of(ACCOUNT_BALANCE, POSTED_BALANCE), violatedChecks(data), data.toString());
        assertEquals(List.of(entry.accountId().toString()), ids(data, ACCOUNT_BALANCE, "accountId"));
        assertEquals(List.of(chargeTx.toString()), ids(data, POSTED_BALANCE, "transactionId"));
    }

    @Test
    void REJECTED_거래에_항목_추가는_거절_거래_검사에만_걸린다() {
        UUID account = walletAccount(walletA, "KRW");
        jdbcTemplate.update("""
                INSERT INTO ledger_entries
                    (id, transaction_id, account_id, currency, entry_type, direction, amount, balance_after)
                VALUES (gen_random_uuid(), ?, ?, 'KRW', 'CHARGE_IN', 'CREDIT', 100, 0)
                """, cancelledTx, account);

        JsonNode data = check();

        // 잔액 검사는 POSTED 항목만 더하므로 REJECTED 거래의 항목은 계정 잔액 비교에 들어가지 않는다
        assertEquals(Set.of(REJECTED_ENTRIES), violatedChecks(data), data.toString());
        assertEquals(List.of(cancelledTx.toString()), ids(data, REJECTED_ENTRIES, "transactionId"));
        assertEquals(1, data.get(REJECTED_ENTRIES).get("violations").get(0).get("entryCount").asLong());
    }

    @Test
    void POSTED_거래를_REJECTED로_바꾸면_잔액_검사와_거절_거래_검사에_걸린다() {
        jdbcTemplate.update("""
                UPDATE ledger_transactions SET status = 'REJECTED', reject_reason = 'CANCELLED_BY_REQUESTER'
                WHERE id = ?
                """, transferTx);

        JsonNode data = check();

        assertEquals(Set.of(ACCOUNT_BALANCE, REJECTED_ENTRIES), violatedChecks(data), data.toString());
        Set<String> transferAccounts = Set.of(walletAccount(walletA, "KRW").toString(),
                walletAccount(walletB, "KRW").toString());
        assertEquals(transferAccounts, Set.copyOf(ids(data, ACCOUNT_BALANCE, "accountId")));
        assertEquals(List.of(transferTx.toString()), ids(data, REJECTED_ENTRIES, "transactionId"));
    }

    @Test
    void 위반이_최대_건수보다_많으면_id_순_앞쪽만_돌려주고_잘림을_표시한다() {
        List<UUID> tampered = new ArrayList<>(List.of(systemAccount("CHARGE_SOURCE", "KRW"),
                systemAccount("FX_POSITION", "KRW"), systemAccount("FEE_REVENUE", "KRW")));
        for (UUID account : tampered) {
            jdbcTemplate.update("UPDATE ledger_accounts SET balance = balance + 1 WHERE id = ?", account);
        }
        List<String> expectedFirstTwo = jdbcTemplate.queryForList(
                "SELECT id::text FROM ledger_accounts WHERE id IN (?, ?, ?) ORDER BY id LIMIT 2", String.class,
                tampered.toArray());

        JsonNode data = check();

        JsonNode accountBalance = data.get(ACCOUNT_BALANCE);
        assertEquals(3, accountBalance.get("violationCount").asLong(), data.toString());
        assertEquals(2, accountBalance.get("returnedCount").asInt());
        assertTrue(accountBalance.get("truncated").asBoolean());
        assertEquals(expectedFirstTwo, ids(data, ACCOUNT_BALANCE, "accountId"));
        assertEquals(2, data.get("violationLimit").asInt());
        assertFalse(data.get("consistent").asBoolean());
        // 통화별 합계는 KRW 한 건(+3)이라 잘리지 않는다
        assertFalse(data.get(CURRENCY_TOTAL).get("truncated").asBoolean());
        assertEquals(3 + 1, data.get("totalViolationCount").asLong());
    }

    // ---- 도구 ----

    private record Entry(UUID id, UUID accountId) {
    }

    private JsonNode check() {
        try {
            HttpResponse<String> response = client.get(INTEGRITY);
            assertEquals(200, response.statusCode(), response.body());
            return client.data(response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID registered(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = client.register(body);
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(client.data(response).get("transactionId").asString());
    }

    /** 위반이 1건 이상인 검사 이름. 전체 위반 수가 검사별 합과 같은지도 확인한다 */
    private static Set<String> violatedChecks(JsonNode data) {
        Set<String> violated = new LinkedHashSet<>();
        long sum = 0;
        for (String check : CHECKS) {
            long count = data.get(check).get("violationCount").asLong();
            sum += count;
            if (count > 0) {
                violated.add(check);
            }
        }
        assertEquals(sum, data.get("totalViolationCount").asLong(), data.toString());
        assertEquals(sum == 0, data.get("consistent").asBoolean(), data.toString());
        return violated;
    }

    private static List<String> ids(JsonNode data, String check, String field) {
        List<String> ids = new ArrayList<>();
        data.get(check).get("violations").forEach(v -> ids.add(v.get(field).asString()));
        return ids;
    }

    private Entry entry(UUID transactionId, String direction) {
        return jdbcTemplate.queryForObject("""
                SELECT id, account_id FROM ledger_entries WHERE transaction_id = ? AND direction = ?
                ORDER BY id LIMIT 1
                """, (rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                transactionId, direction);
    }

    private UUID systemAccount(String accountType, String currency) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ledger_accounts WHERE owner_type = 'SYSTEM' AND account_type = ? AND currency = ?
                """, UUID.class, accountType, currency);
    }

    private UUID walletAccount(UUID walletId, String currency) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ledger_accounts WHERE wallet_id = ? AND account_type = 'WALLET' AND currency = ?
                """, UUID.class, walletId, currency);
    }
}
