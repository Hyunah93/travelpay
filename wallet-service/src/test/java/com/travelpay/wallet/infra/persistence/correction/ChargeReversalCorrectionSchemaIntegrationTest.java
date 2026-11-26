package com.travelpay.wallet.infra.persistence.correction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V7 charge_reversal_corrections 제약을 실제 Postgres(Flyway 적용)에서 확인한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.correction.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeReversalCorrectionSchemaIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String RESULT_CHECK = "ck_charge_reversal_corrections_result";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChargeReversalCorrectionRepositoryImpl repository;

    private UUID walletId;
    private UUID resultId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs, "
                + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId,
                UUID.randomUUID());
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation_runs (id, period_from, period_to, status, started_at, finished_at, run_type)
                VALUES (?, '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'COMPLETED', now(), now(), 'MANUAL')
                """, runId);
        resultId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation_results (id, run_id, charge_id, mismatch_type, detail, snapshot, detected_at)
                VALUES (?, ?, ?, 'CREDIT_AND_REFUND_BOTH', 'd', '{}'::jsonb, now())
                """, resultId, runId, charge());
    }

    @Test
    void 상태별로_맞게_채운_행은_저장된다() {
        insert(charge(), "PENDING", null, null, null);
        insert(charge(), "NEEDS_REVIEW", null, null, null);
        insert(charge(), "SUCCEEDED", UUID.randomUUID(), 0L, 10_000L);

        assertEquals(3, jdbcTemplate.queryForObject("SELECT count(*) FROM charge_reversal_corrections",
                Integer.class));
    }

    /**
     * 세 값이 아닌 status는 결과 칸 CHECK(ck_..._result)에도 걸려 Postgres가 어느 쪽을 먼저 보고할지 정해져 있지 않다.
     * 그래서 거절 자체와 status CHECK의 정의를 따로 확인한다
     */
    @Test
    void status는_PENDING_SUCCEEDED_NEEDS_REVIEW만() {
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> insert(charge(), "FAILED", null, null, null));
        assertTrue(e.getMessage().contains("ck_charge_reversal_corrections_status")
                || e.getMessage().contains(RESULT_CHECK), e.getMessage());
        String definition = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_charge_reversal_corrections_status'
                """, String.class);
        assertTrue(definition.contains("'PENDING'") && definition.contains("'SUCCEEDED'")
                && definition.contains("'NEEDS_REVIEW'") && !definition.contains("'FAILED'"), definition);
    }

    @Test
    void executed_by는_SYSTEM만() {
        assertRejected("ck_charge_reversal_corrections_executed_by", () -> jdbcTemplate.update("""
                INSERT INTO charge_reversal_corrections (id, charge_id, reconciliation_result_id, status,
                    next_attempt_at, executed_by, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', now(), 'OPERATOR', now(), now())
                """, UUID.randomUUID(), charge(), resultId));
    }

    @Test
    void 같은_충전은_1행만() {
        UUID chargeId = charge();
        insert(chargeId, "PENDING", null, null, null);

        DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                () -> insert(chargeId, "PENDING", null, null, null));
        assertTrue(e.getMessage().contains("uk_charge_reversal_corrections_charge"), e.getMessage());
    }

    @Test
    void 등록은_같은_충전이_이미_있으면_상태와_관계없이_바꾸지_않는다() {
        UUID chargeId = charge();
        insert(chargeId, "NEEDS_REVIEW", null, null, null);

        Integer inserted = transactionTemplate.execute(status ->
                repository.registerIfAbsent(chargeId, resultId, Instant.now()));

        assertEquals(0, inserted);
        assertEquals("NEEDS_REVIEW", jdbcTemplate.queryForObject(
                "SELECT status FROM charge_reversal_corrections WHERE charge_id = ?", String.class, chargeId));
    }

    @Test
    void SUCCEEDED인데_결과_칸_하나라도_NULL이면_거절() {
        assertRejected(RESULT_CHECK, () -> insert(charge(), "SUCCEEDED", UUID.randomUUID(), 4_000L, null));
        assertRejected(RESULT_CHECK, () -> insert(charge(), "SUCCEEDED", UUID.randomUUID(), null, 6_000L));
        assertRejected(RESULT_CHECK, () -> insert(charge(), "SUCCEEDED", null, 4_000L, 6_000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "NEEDS_REVIEW"})
    void PENDING_NEEDS_REVIEW인데_결과_칸이_하나라도_있으면_거절(String status) {
        assertRejected(RESULT_CHECK, () -> insert(charge(), status, UUID.randomUUID(), null, null));
        assertRejected(RESULT_CHECK, () -> insert(charge(), status, null, 0L, null));
        assertRejected(RESULT_CHECK, () -> insert(charge(), status, null, null, 0L));
    }

    @Test
    void 금액은_0_이상() {
        assertRejected("ck_charge_reversal_corrections_amounts",
                () -> insert(charge(), "SUCCEEDED", UUID.randomUUID(), -1L, 10_001L));
        assertRejected("ck_charge_reversal_corrections_amounts",
                () -> insert(charge(), "SUCCEEDED", UUID.randomUUID(), 10_001L, -1L));
    }

    @Test
    void 충전과_대사_결과는_FK() {
        assertRejected("fk_charge_reversal_corrections_charge",
                () -> insert(UUID.randomUUID(), "PENDING", null, null, null));
        assertRejected("fk_charge_reversal_corrections_reconciliation_result", () -> jdbcTemplate.update("""
                INSERT INTO charge_reversal_corrections (id, charge_id, reconciliation_result_id, status,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', now(), now(), now())
                """, UUID.randomUUID(), charge(), UUID.randomUUID()));
    }

    private void insert(UUID chargeId, String status, UUID ledgerTxId, Long recovered, Long receivable) {
        jdbcTemplate.update("""
                INSERT INTO charge_reversal_corrections (id, charge_id, reconciliation_result_id, status,
                    attempt_count, next_attempt_at, ledger_tx_id, recovered_amount, receivable_amount, executed_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, now(), ?, ?, ?, 'SYSTEM', now(), now())
                """, UUID.randomUUID(), chargeId, resultId, status, ledgerTxId, recovered, receivable);
    }

    private static void assertRejected(String constraint, Executable insert) {
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class, insert);
        assertTrue(e.getMessage().contains(constraint), e.getMessage());
    }

    private UUID charge() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO charge_requests (id, user_id, wallet_id, amount, currency, bank_code, account_number_enc,
                    holder_name_snapshot, status, idempotency_key, bank_request_id, refund_request_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, 10000, 'KRW', '004', 'enc', '홍길동', 'COMPLETED', ?, ?, ?, now(), now())
                """, id, UUID.randomUUID(), walletId, UUID.randomUUID(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        return id;
    }
}
