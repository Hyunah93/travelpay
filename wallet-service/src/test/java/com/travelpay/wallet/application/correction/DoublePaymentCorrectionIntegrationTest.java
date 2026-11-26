package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.application.correction.DoublePaymentCorrectionProcessor.Outcome;
import com.travelpay.wallet.application.correction.dto.ChargeReversalResult;
import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.ChargeReconciliationService;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionTarget;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRunType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 Postgres(Flyway 적용)에서 이중 지급 자동 정정을 검증한다. 은행·원장 조회와 원장 취소 분개 요청은 테스트 대역이고,
 * 스케줄 등록은 끄고 스케줄러를 직접 만들어 correct()를 호출한다. Docker가 없으면 건너뛴다.
 * 시간 경과는 기다리지 않고 next_attempt_at을 DB에서 직접 과거로 바꿔 만든다.
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
class DoublePaymentCorrectionIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");

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

    @MockitoBean
    private BankTransactionQueryClient bankClient;

    @MockitoBean
    private LedgerTransactionQueryClient ledgerClient;

    @MockitoBean
    private LedgerChargeReversalClient reversalClient;

    @Autowired
    private DoublePaymentCorrectionSteps steps;

    @Autowired
    private DoublePaymentCorrectionProcessor processor;

    @Autowired
    private ChargeReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID walletId;
    private SimpleMeterRegistry meterRegistry;
    private DoublePaymentCorrectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".reconciliation_results, " + SCHEMA + ".reconciliation_runs, "
                + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId,
                UUID.randomUUID());
        when(bankClient.listTransactions(any(), any())).thenReturn(List.of());
        when(ledgerClient.listChargeTransactions(any(), any())).thenReturn(List.of());
        when(ledgerClient.listChargeReversals(any(), any())).thenReturn(List.of());
        when(bankClient.findTransaction(anyString())).thenReturn(SourceLookup.notFound());
        when(ledgerClient.findChargeTransaction(any())).thenReturn(SourceLookup.notFound());
        when(ledgerClient.findChargeReversal(any())).thenReturn(SourceLookup.notFound());
        meterRegistry = new SimpleMeterRegistry();
        scheduler = scheduler(10, System::nanoTime);
    }

    // ---------------------------------------------------------------- 정상

    @Test
    void 이중_지급이면_취소_분개를_1회_요청하고_SUCCEEDED와_금액을_저장() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        UUID ledgerTxId = UUID.randomUUID();
        when(reversalClient.reverse(eq(f.chargeId()), eq(AMOUNT), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(ledgerTxId, 4_000, 6_000));

        scheduler.correct();

        Map<String, Object> row = row(f);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(ledgerTxId, row.get("ledger_tx_id"));
        assertEquals(4_000L, row.get("recovered_amount"));
        assertEquals(6_000L, row.get("receivable_amount"));
        assertEquals(0, row.get("attempt_count"));
        assertNull(row.get("last_error"));
        verify(reversalClient, times(1)).reverse(any(), anyLong(), anyString());
    }

    @Test
    void REFUNDED_충전도_정정_대상() {
        Fixture f = doublePaid(newCorrection("REFUNDED"));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(UUID.randomUUID(), AMOUNT, 0));

        scheduler.correct();

        assertEquals("SUCCEEDED", row(f).get("status"));
    }

    @ParameterizedTest(name = "회수 {0} · 미수 {1}")
    @CsvSource({"10000, 0", "4000, 6000", "0, 10000"})
    void 이미_정상_취소_분개가_있으면_요청_없이_항목에서_금액을_계산해_SUCCEEDED(long recovered, long receivable) {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        LedgerTransactionRecord reversal = reversal(f, AMOUNT, recovered, receivable, walletId);
        when(ledgerClient.findChargeReversal(f.chargeId())).thenReturn(SourceLookup.found(reversal));

        scheduler.correct();

        Map<String, Object> row = row(f);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(reversal.transactionId(), row.get("ledger_tx_id"));
        assertEquals(recovered, row.get("recovered_amount"));
        assertEquals(receivable, row.get("receivable_amount"));
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 사람 확인 (요청 없음)

    @Test
    void 구조가_틀린_취소_분개가_있으면_요청_없이_REVERSAL_MALFORMED() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(ledgerClient.findChargeReversal(f.chargeId()))
                .thenReturn(SourceLookup.found(reversal(f, AMOUNT, 4_000, 6_000, UUID.randomUUID())));

        scheduler.correct();

        assertNeedsReview(f, "REVERSAL_MALFORMED", 0);
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 환불이_없으면_NO_REFUND() {
        Fixture f = newCorrection("COMPLETED");
        when(ledgerClient.findChargeTransaction(f.chargeId())).thenReturn(SourceLookup.found(posted(f, AMOUNT)));

        scheduler.correct();

        assertNeedsReview(f, "NO_REFUND", 0);
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 적립이_없으면_NO_CREDIT() {
        Fixture f = newCorrection("COMPLETED");
        when(bankClient.findTransaction(f.refundRequestId())).thenReturn(SourceLookup.found(refund(f, AMOUNT)));

        scheduler.correct();

        assertNeedsReview(f, "NO_CREDIT", 0);
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 환불과_적립이_모두_없으면_먼저_보는_NO_REFUND() {
        Fixture f = newCorrection("COMPLETED");

        scheduler.correct();

        assertNeedsReview(f, "NO_REFUND", 0);
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 환불_금액이_충전과_다르면_AMOUNT_MISMATCH() {
        Fixture f = newCorrection("COMPLETED");
        when(bankClient.findTransaction(f.refundRequestId())).thenReturn(SourceLookup.found(refund(f, AMOUNT - 1)));
        when(ledgerClient.findChargeTransaction(f.chargeId())).thenReturn(SourceLookup.found(posted(f, AMOUNT)));

        scheduler.correct();

        assertNeedsReview(f, "AMOUNT_MISMATCH", 0);
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 충전이_COMPLETED_REFUNDED가_아니면_조회_없이_CHARGE_STATUS() {
        Fixture f = doublePaid(newCorrection("MANUAL_REVIEW"));

        scheduler.correct();

        assertNeedsReview(f, "CHARGE_STATUS", 0);
        verify(bankClient, never()).findTransaction(anyString());
        verify(ledgerClient, never()).findChargeReversal(any());
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 재조회 실패

    @Test
    void 재조회가_Unavailable이면_PENDING_attempt_그대로_요청_없음_다음_시도는_선점이_미룬_30초_뒤() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(ledgerClient.findChargeReversal(f.chargeId())).thenReturn(SourceLookup.unavailable("503"));
        Instant before = instant(row(f).get("updated_at"));

        scheduler.correct();

        Map<String, Object> row = row(f);
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, row.get("attempt_count"));
        assertNull(row.get("last_error"));
        assertNotEquals(before, instant(row.get("updated_at")), "선점이 updated_at을 바꿈");
        assertEquals(Duration.ofSeconds(30), gap(row));
        assertTrue(steps.findDueTargets(10).isEmpty());
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 조회_포트의_예상_밖_예외도_Unavailable로_본다() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(bankClient.findTransaction(f.refundRequestId())).thenThrow(new IllegalStateException("boom"));

        scheduler.correct();

        assertEquals("PENDING", row(f).get("status"));
        assertEquals(0, row(f).get("attempt_count"));
        verify(reversalClient, never()).reverse(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 원장 결과 모름

    @Test
    void 결과_모름이면_attempt_1_다음_시도_30초_뒤이고_그_전에는_대상이_아님() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Unknown("feign.RetryableException"));

        scheduler.correct();

        Map<String, Object> row = row(f);
        assertEquals("PENDING", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
        assertTrue(((String) row.get("last_error")).startsWith("LEDGER_UNKNOWN"), (String) row.get("last_error"));
        assertEquals(Duration.ofSeconds(30), gap(row));
        assertTrue(steps.findDueTargets(10).isEmpty());

        scheduler.correct();

        verify(reversalClient, times(1)).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 결과_모름이_이어지면_30_60_120_240초_간격이고_5번째에_RETRY_EXHAUSTED() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Unknown("feign.RetryableException"));

        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            makeDue(f);
            scheduler.correct();
            Map<String, Object> row = row(f);
            assertEquals("PENDING", row.get("status"));
            assertEquals(i, row.get("attempt_count"));
            gaps.add(gap(row));
        }
        makeDue(f);
        scheduler.correct();

        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
                Duration.ofSeconds(240)), gaps);
        assertNeedsReview(f, "RETRY_EXHAUSTED", 5);
        verify(reversalClient, times(5)).reverse(any(), anyLong(), anyString());

        makeDue(f);
        scheduler.correct();
        verify(reversalClient, times(5)).reverse(any(), anyLong(), anyString());
    }

    @Test
    void 결과_모름_다음_주기에_Reversed면_SUCCEEDED() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        UUID ledgerTxId = UUID.randomUUID();
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Unknown("feign.RetryableException"))
                .thenReturn(new ChargeReversalResult.Reversed(ledgerTxId, AMOUNT, 0));

        scheduler.correct();
        makeDue(f);
        scheduler.correct();

        Map<String, Object> row = row(f);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(ledgerTxId, row.get("ledger_tx_id"));
        assertEquals(1, row.get("attempt_count"));
        assertNull(row.get("last_error"));
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of(new ChargeReversalResult.NotReversible(), "NOT_REVERSIBLE"),
                Arguments.of(new ChargeReversalResult.Suspended(), "WALLET_SUSPENDED"),
                Arguments.of(new ChargeReversalResult.OriginalNotFound(), "ORIGINAL_NOT_FOUND"),
                Arguments.of(new ChargeReversalResult.Invalid("ledger response invalid: amount sum"),
                        "INVALID_RESPONSE"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("rejections")
    void 원장이_받지_않으면_NEEDS_REVIEW와_분류_코드(ChargeReversalResult result, String code) {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(reversalClient.reverse(any(), anyLong(), anyString())).thenReturn(result);

        scheduler.correct();

        assertNeedsReview(f, code, 0);
        verify(reversalClient, times(1)).reverse(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 선점·임대·토큰

    @Test
    void 같은_행을_두_처리기가_동시에_선점하면_한쪽만_성공하고_요청은_최대_1회() throws Exception {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(UUID.randomUUID(), AMOUNT, 0));
        ChargeReversalCorrectionTarget target = steps.findDueTargets(10).getFirst();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    Optional<Instant> token = steps.claim(target);
                    token.ifPresent(t -> processor.process(target, t));
                    return token.isPresent();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int claimed = 0;
            for (Future<Boolean> future : futures) {
                claimed += future.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, claimed);
        } finally {
            pool.shutdownNow();
        }
        verify(reversalClient, times(1)).reverse(any(), anyLong(), anyString());
        assertEquals(1, correctionCount());
        assertEquals("SUCCEEDED", row(f).get("status"));
    }

    @Test
    void 두_스케줄러가_동시에_돌아도_요청은_최대_1회이고_정정_기록은_1건() throws Exception {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(UUID.randomUUID(), AMOUNT, 0));
        DoublePaymentCorrectionScheduler other = scheduler(10, System::nanoTime);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (DoublePaymentCorrectionScheduler s : List.of(scheduler, other)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    s.correct();
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(reversalClient, times(1)).reverse(any(), anyLong(), anyString());
        assertEquals(1, correctionCount());
        assertEquals("SUCCEEDED", row(f).get("status"));
    }

    @Test
    void 임대가_끝나_다른_처리기가_다시_선점하면_이전_처리기의_기록은_0건이고_새_처리기의_기록이_반영() {
        Fixture f = doublePaid(newCorrection("COMPLETED"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(first, AMOUNT, 0))
                .thenReturn(new ChargeReversalResult.Reversed(second, 4_000, 6_000));

        ChargeReversalCorrectionTarget targetA = steps.findDueTargets(10).getFirst();
        Instant tokenA = steps.claim(targetA).orElseThrow();
        // 30초를 기다리지 않고 임대 만료를 만든다
        makeDue(f);
        ChargeReversalCorrectionTarget targetB = steps.findDueTargets(10).getFirst();
        Instant tokenB = steps.claim(targetB).orElseThrow();
        assertNotEquals(tokenA, tokenB);

        assertFalse(steps.recordSucceeded(f.correctionId(), tokenA, first, AMOUNT, 0), "A의 토큰으로는 0건");
        assertEquals(Outcome.NOT_RECORDED, processor.process(targetA, tokenA));
        assertEquals("PENDING", row(f).get("status"));

        assertEquals(Outcome.SUCCEEDED, processor.process(targetB, tokenB));
        Map<String, Object> row = row(f);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(second, row.get("ledger_tx_id"));
        assertEquals(4_000L, row.get("recovered_amount"));
    }

    @Test
    void 선점한_행은_다음_주기_조회에_나오지_않는다() {
        Fixture f = newCorrection("COMPLETED");
        ChargeReversalCorrectionTarget target = steps.findDueTargets(10).getFirst();
        Instant before = target.updatedAt();

        Instant token = steps.claim(target).orElseThrow();

        assertTrue(steps.findDueTargets(10).isEmpty());
        assertEquals(token, instant(row(f).get("updated_at")), "선점 토큰은 바뀐 updated_at");
        assertNotEquals(before, token);
        assertEquals(Duration.ofSeconds(30), gap(row(f)));
        assertTrue(steps.claim(target).isEmpty(), "같은 읽은 값으로 다시 선점되지 않는다");
    }

    @Test
    void next_attempt_at이_남은_건은_대상이_아니고_오래된_순으로_조회() {
        Fixture later = newCorrection("COMPLETED");
        Fixture earlier = newCorrection("COMPLETED");
        Fixture notDue = newCorrection("COMPLETED");
        jdbcTemplate.update("UPDATE charge_reversal_corrections SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE id = ?", later.correctionId());
        jdbcTemplate.update("UPDATE charge_reversal_corrections SET next_attempt_at = now() - interval '2 minute' "
                + "WHERE id = ?", earlier.correctionId());
        jdbcTemplate.update("UPDATE charge_reversal_corrections SET next_attempt_at = now() + interval '1 minute' "
                + "WHERE id = ?", notDue.correctionId());

        List<UUID> found = steps.findDueTargets(10).stream().map(ChargeReversalCorrectionTarget::id).toList();

        assertEquals(List.of(earlier.correctionId(), later.correctionId()), found);
    }

    // ---------------------------------------------------------------- 배치·마감

    @Test
    void 배치가_가득_차면_같은_주기에서_이어서_처리() {
        List<Fixture> fixtures = List.of(doublePaid(newCorrection("COMPLETED")), doublePaid(newCorrection("COMPLETED")),
                doublePaid(newCorrection("COMPLETED")));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenAnswer(inv -> new ChargeReversalResult.Reversed(UUID.randomUUID(), AMOUNT, 0));

        scheduler(2, System::nanoTime).correct();

        fixtures.forEach(f -> assertEquals("SUCCEEDED", row(f).get("status")));
    }

    @Test
    void 주기_마감이_지나면_남은_대상은_다음_주기로() {
        List<Fixture> fixtures = List.of(doublePaid(newCorrection("COMPLETED")), doublePaid(newCorrection("COMPLETED")),
                doublePaid(newCorrection("COMPLETED")));
        when(reversalClient.reverse(any(), anyLong(), anyString()))
                .thenAnswer(inv -> new ChargeReversalResult.Reversed(UUID.randomUUID(), AMOUNT, 0));
        // 첫 호출(마감 계산)은 0, 그 뒤는 마감(8초)을 넘은 시각
        AtomicInteger calls = new AtomicInteger();
        DoublePaymentCorrectionScheduler limited = scheduler(2,
                () -> calls.getAndIncrement() == 0 ? 0 : Duration.ofSeconds(9).toNanos());

        limited.correct();

        assertEquals(2, statusCount("SUCCEEDED"));
        assertEquals(1, statusCount("PENDING"));

        limited.correct();

        fixtures.forEach(f -> assertEquals("SUCCEEDED", row(f).get("status")));
    }

    // ---------------------------------------------------------------- 지표

    @Test
    void 주기마다_NEEDS_REVIEW와_PENDING_건수를_지표로_갱신() {
        newCorrection("MANUAL_REVIEW");
        Fixture unavailable = doublePaid(newCorrection("COMPLETED"));
        when(ledgerClient.findChargeReversal(unavailable.chargeId())).thenReturn(SourceLookup.unavailable("503"));
        Fixture notDue = newCorrection("COMPLETED");
        jdbcTemplate.update("UPDATE charge_reversal_corrections SET next_attempt_at = now() + interval '1 hour' "
                + "WHERE id = ?", notDue.correctionId());

        scheduler.correct();

        assertEquals(1.0, meterRegistry.get(DoublePaymentCorrectionScheduler.NEEDS_REVIEW_METRIC).gauge().value());
        // 재조회 실패로 다시 시도 중인 건도 PENDING에 포함
        assertEquals(2.0, meterRegistry.get(DoublePaymentCorrectionScheduler.PENDING_METRIC).gauge().value());
    }

    // ---------------------------------------------------------------- 대사부터 끝까지

    @Test
    void 대사가_등록한_정정을_스케줄러가_바로잡으면_다음_대사에서_CREDIT_AND_REFUND_BOTH_없음() {
        Fixture f = completedCharge(FROM.plus(Duration.ofHours(1)));
        BankTransactionRecord debit = new BankTransactionRecord(f.bankRequestId(), f.bankTxId(), "DEBIT", "SUCCEEDED",
                AMOUNT);
        BankTransactionRecord refund = refund(f, AMOUNT);
        LedgerTransactionRecord posted = posted(f, AMOUNT);
        when(bankClient.listTransactions(any(), any())).thenReturn(List.of(debit, refund));
        when(ledgerClient.listChargeTransactions(any(), any())).thenReturn(List.of(posted));
        when(bankClient.findTransaction(f.refundRequestId())).thenReturn(SourceLookup.found(refund));
        when(ledgerClient.findChargeTransaction(f.chargeId())).thenReturn(SourceLookup.found(posted));

        ReconciliationRunResult first = reconciliationService.run(FROM, TO, null, ReconciliationRunType.MANUAL);
        assertTrue(types(first.runId(), f.chargeId()).contains("CREDIT_AND_REFUND_BOTH"));
        UUID correctionId = jdbcTemplate.queryForObject(
                "SELECT id FROM charge_reversal_corrections WHERE charge_id = ?", UUID.class, f.chargeId());
        Fixture registered = new Fixture(f.chargeId(), correctionId, f.bankRequestId(), f.refundRequestId(),
                f.bankTxId(), f.ledgerRef());

        LedgerTransactionRecord reversal = reversal(f, AMOUNT, 4_000, 6_000, walletId);
        when(reversalClient.reverse(eq(f.chargeId()), eq(AMOUNT), anyString()))
                .thenReturn(new ChargeReversalResult.Reversed(reversal.transactionId(), 4_000, 6_000));
        // 등록 시각은 앱 시계, 대상 조회는 DB now() 기준이라 시계 차이에 기대지 않도록 바로 대상이 되게 한다
        makeDue(registered);
        scheduler.correct();
        assertEquals("SUCCEEDED", row(registered).get("status"));

        // 원장 대역이 이제 취소 분개를 돌려준다
        when(ledgerClient.listChargeReversals(any(), any())).thenReturn(List.of(reversal));
        when(ledgerClient.findChargeReversal(f.chargeId())).thenReturn(SourceLookup.found(reversal));
        ReconciliationRunResult second = reconciliationService.run(FROM, TO, null, ReconciliationRunType.MANUAL);

        assertFalse(types(second.runId(), f.chargeId()).contains("CREDIT_AND_REFUND_BOTH"),
                types(second.runId(), f.chargeId()).toString());
        assertEquals(1, correctionCount());
        assertEquals("SUCCEEDED", row(registered).get("status"));
    }

    // ---------------------------------------------------------------- 준비

    private record Fixture(UUID chargeId, UUID correctionId, String bankRequestId, String refundRequestId,
                           UUID bankTxId, UUID ledgerRef) {
    }

    private DoublePaymentCorrectionScheduler scheduler(int batchSize, java.util.function.LongSupplier clock) {
        return new DoublePaymentCorrectionScheduler(steps, processor, meterRegistry, batchSize, "10s", clock);
    }

    /** 충전 + 대사 실행·CREDIT_AND_REFUND_BOTH 결과 + 지금 처리할 PENDING 정정 대상 */
    private Fixture newCorrection(String chargeStatus) {
        Fixture charge = insertCharge(chargeStatus, Instant.now());
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation_runs (id, period_from, period_to, status, started_at, finished_at, run_type)
                VALUES (?, '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'COMPLETED', now(), now(), 'MANUAL')
                """, runId);
        UUID resultId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation_results (id, run_id, charge_id, mismatch_type, detail, snapshot, detected_at)
                VALUES (?, ?, ?, 'CREDIT_AND_REFUND_BOTH', 'd', '{}'::jsonb, now())
                """, resultId, runId, charge.chargeId());
        UUID correctionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO charge_reversal_corrections (id, charge_id, reconciliation_result_id, status,
                    attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', 0, now() - interval '1 second', now(), now())
                """, correctionId, charge.chargeId(), resultId);
        return new Fixture(charge.chargeId(), correctionId, charge.bankRequestId(), charge.refundRequestId(),
                charge.bankTxId(), charge.ledgerRef());
    }

    private Fixture completedCharge(Instant createdAt) {
        return insertCharge("COMPLETED", createdAt);
    }

    /** 출금·적립·환불 ID가 모두 저장된 충전 (이중 지급 COMPLETED처럼 대사 ID 비교가 맞도록) */
    private Fixture insertCharge(String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        String bankRequestId = UUID.randomUUID().toString();
        String refundRequestId = UUID.randomUUID().toString();
        UUID bankTxId = UUID.randomUUID();
        UUID ledgerRef = UUID.randomUUID();
        UUID refundBankTxId = UUID.nameUUIDFromBytes(refundRequestId.getBytes());
        jdbcTemplate.update("""
                INSERT INTO charge_requests (id, user_id, wallet_id, amount, currency, bank_code, account_number_enc,
                    holder_name_snapshot, status, idempotency_key, bank_request_id, refund_request_id,
                    bank_tx_id, refund_bank_tx_id, ledger_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'KRW', '004', 'enc', '홍길동', ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, id, UUID.randomUUID(), walletId, AMOUNT, status, UUID.randomUUID(), bankRequestId,
                refundRequestId, bankTxId, refundBankTxId, ledgerRef, createdAt.toString(), createdAt.toString());
        return new Fixture(id, null, bankRequestId, refundRequestId, bankTxId, ledgerRef);
    }

    /** 은행 환불 SUCCEEDED + 원장 적립 POSTED (금액 일치), 취소 분개 없음 */
    private Fixture doublePaid(Fixture f) {
        when(bankClient.findTransaction(f.refundRequestId())).thenReturn(SourceLookup.found(refund(f, AMOUNT)));
        when(ledgerClient.findChargeTransaction(f.chargeId())).thenReturn(SourceLookup.found(posted(f, AMOUNT)));
        return f;
    }

    private static BankTransactionRecord refund(Fixture f, long amount) {
        return new BankTransactionRecord(f.refundRequestId(), UUID.nameUUIDFromBytes(f.refundRequestId().getBytes()),
                "REFUND", "SUCCEEDED", amount);
    }

    private LedgerTransactionRecord posted(Fixture f, long amount) {
        return new LedgerTransactionRecord(f.ledgerRef(), "POSTED", "CHARGE", f.chargeId(), List.of(
                new LedgerEntryRecord("SYSTEM", null, "CHARGE_SOURCE", "DEBIT", "KRW", amount, "CHARGE_OUT"),
                new LedgerEntryRecord("USER", walletId, "WALLET", "CREDIT", "KRW", amount, "CHARGE_IN")));
    }

    /** 충전 취소 분개. 회수(recovered)·미수(receivable)가 0이면 그 항목을 넣지 않는다 */
    private static LedgerTransactionRecord reversal(Fixture f, long inAmount, long recovered, long receivable,
                                                    UUID outWallet) {
        List<LedgerEntryRecord> entries = new ArrayList<>();
        if (recovered > 0) {
            entries.add(new LedgerEntryRecord("USER", outWallet, "WALLET", "DEBIT", "KRW", recovered,
                    "CHARGE_REVERSAL_OUT"));
        }
        if (receivable > 0) {
            entries.add(new LedgerEntryRecord("SYSTEM", null, "RECEIVABLE", "DEBIT", "KRW", receivable,
                    "CHARGE_REVERSAL_RECEIVABLE"));
        }
        entries.add(new LedgerEntryRecord("SYSTEM", null, "CHARGE_SOURCE", "CREDIT", "KRW", inAmount,
                "CHARGE_REVERSAL_IN"));
        return new LedgerTransactionRecord(UUID.randomUUID(), "POSTED", "CHARGE_REVERSAL", f.chargeId(), entries);
    }

    /** 다음 시도 시각을 DB 기준 과거로 (대기·임대 만료를 기다리지 않고 만든다) */
    private void makeDue(Fixture f) {
        jdbcTemplate.update("UPDATE charge_reversal_corrections SET next_attempt_at = now() - interval '1 second' "
                + "WHERE id = ?", f.correctionId());
    }

    // ---------------------------------------------------------------- 확인

    private void assertNeedsReview(Fixture f, String code, int attempts) {
        Map<String, Object> row = row(f);
        assertEquals("NEEDS_REVIEW", row.get("status"));
        String lastError = (String) row.get("last_error");
        assertTrue(lastError.equals(code) || lastError.startsWith(code + ": "), lastError);
        assertEquals(attempts, row.get("attempt_count"));
        assertNull(row.get("ledger_tx_id"));
        assertNull(row.get("recovered_amount"));
        assertNull(row.get("receivable_amount"));
    }

    private Map<String, Object> row(Fixture f) {
        return jdbcTemplate.queryForMap("SELECT * FROM charge_reversal_corrections WHERE id = ?", f.correctionId());
    }

    /** next_attempt_at − updated_at. 같은 UPDATE 문의 now()로 정하므로 정확히 대기 시간이다 */
    private static Duration gap(Map<String, Object> row) {
        return Duration.between(instant(row.get("updated_at")), instant(row.get("next_attempt_at")));
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) value;
    }

    private int correctionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge_reversal_corrections", Integer.class);
    }

    private int statusCount(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge_reversal_corrections WHERE status = ?",
                Integer.class, status);
    }

    private List<String> types(UUID runId, UUID chargeId) {
        return jdbcTemplate.queryForList("""
                SELECT mismatch_type FROM reconciliation_results
                 WHERE run_id = ? AND charge_id = ? ORDER BY mismatch_type
                """, String.class, runId, chargeId);
    }
}
