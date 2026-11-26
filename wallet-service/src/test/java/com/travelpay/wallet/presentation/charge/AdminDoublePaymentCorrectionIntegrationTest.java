package com.travelpay.wallet.presentation.charge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 이중 지급 정정 조회 API를 실제 Postgres에서 검증한다. Docker가 없으면 건너뛴다.
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
class AdminDoublePaymentCorrectionIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String PATH = "/api/v1/admin/charges/double-payment-corrections";
    private static final String RECONCILIATION_PATH = "/api/v1/admin/charges/reconciliations";

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
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID walletId;
    private UUID resultId;
    private Instant now;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void 목록은_최근_등록순이고_모든_필드를_보여준다() throws Exception {
        UUID oldest = insert("PENDING", now.minus(Duration.ofHours(3)), null, null, null, null);
        UUID ledgerTxId = UUID.randomUUID();
        UUID succeeded = insert("SUCCEEDED", now.minus(Duration.ofHours(2)), ledgerTxId, 4_000L, 6_000L, null);
        UUID review = insert("NEEDS_REVIEW", now.minus(Duration.ofHours(1)), null, null, null, "NO_REFUND: 은행 환불 SUCCEEDED 없음");

        asMaster(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(review.toString(), succeeded.toString(),
                        oldest.toString())))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[1].reconciliationResultId").value(resultId.toString()))
                .andExpect(jsonPath("$.data.items[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.items[1].attemptCount").value(0))
                .andExpect(jsonPath("$.data.items[1].nextAttemptAt").exists())
                .andExpect(jsonPath("$.data.items[1].ledgerTxId").value(ledgerTxId.toString()))
                .andExpect(jsonPath("$.data.items[1].recoveredAmount").value(4_000))
                .andExpect(jsonPath("$.data.items[1].receivableAmount").value(6_000))
                .andExpect(jsonPath("$.data.items[1].lastError").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].chargeId").exists())
                .andExpect(jsonPath("$.data.items[1].createdAt").exists())
                .andExpect(jsonPath("$.data.items[1].updatedAt").exists())
                .andExpect(jsonPath("$.data.items[0].lastError").value("NO_REFUND: 은행 환불 SUCCEEDED 없음"))
                .andExpect(jsonPath("$.data.items[0].ledgerTxId").value(nullValue()));
    }

    @Test
    void status로_거르고_페이지로_나눈다() throws Exception {
        UUID first = insert("NEEDS_REVIEW", now.minus(Duration.ofHours(3)), null, null, null, "CHARGE_STATUS");
        UUID second = insert("NEEDS_REVIEW", now.minus(Duration.ofHours(2)), null, null, null, "NO_CREDIT");
        insert("PENDING", now.minus(Duration.ofHours(1)), null, null, null, null);

        asMaster(get(PATH).param("status", "NEEDS_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(second.toString(), first.toString())))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        asMaster(get(PATH).param("status", "NEEDS_REVIEW").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(first.toString())))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        asMaster(get(PATH).param("status", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 잘못된_페이지와_status는_400() throws Exception {
        asMaster(get(PATH).param("page", "-1")).andExpect(status().isBadRequest());
        asMaster(get(PATH).param("size", "0")).andExpect(status().isBadRequest());
        asMaster(get(PATH).param("status", "FAILED")).andExpect(status().isBadRequest());
    }

    @Test
    void MASTER가_아니면_403() throws Exception {
        mockMvc.perform(get(PATH).header("User-Id", UUID.randomUUID().toString()).header("User-Role", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증_헤더가_없으면_기존_관리자_API와_같은_응답() throws Exception {
        MvcResult existing = mockMvc.perform(get(RECONCILIATION_PATH)).andReturn();
        MvcResult corrections = mockMvc.perform(get(PATH)).andReturn();

        assertEquals(existing.getResponse().getStatus(), corrections.getResponse().getStatus());
        assertEquals(existing.getResponse().getContentAsString(), corrections.getResponse().getContentAsString());
    }

    private UUID insert(String status, Instant createdAt, UUID ledgerTxId, Long recovered, Long receivable,
                        String lastError) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO charge_reversal_corrections (id, charge_id, reconciliation_result_id, status,
                    attempt_count, next_attempt_at, last_error, ledger_tx_id, recovered_amount, receivable_amount,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, now(), ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, id, charge(), resultId, status, lastError, ledgerTxId, recovered, receivable,
                createdAt.toString(), createdAt.toString());
        return id;
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

    private ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER"));
    }
}
