package com.travelpay.ledger;

import com.travelpay.ledger.LedgerTestClient.Call;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.travelpay.ledger.LedgerTestClient.body;
import static com.travelpay.ledger.LedgerTestClient.chargeBody;
import static com.travelpay.ledger.LedgerTestClient.describe;
import static com.travelpay.ledger.LedgerTestClient.system;
import static com.travelpay.ledger.LedgerTestClient.transferBody;
import static com.travelpay.ledger.LedgerTestClient.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 충전 취소 분개 API(POST /internal/v1/ledger/transactions/charge-reversals)를 실제 Postgres·실제 HTTP로 확인한다.
 * <ul>
 *     <li>금액: 잠근 뒤의 지갑 잔액만큼 회수(CHARGE_REVERSAL_OUT), 부족분은 미수(CHARGE_REVERSAL_RECEIVABLE),
 *         충전 원천에는 원금액(CHARGE_REVERSAL_IN)</li>
 *     <li>거절(원거래 없음 404, REJECTED·구조 다름 422)은 아무 행도 남기지 않는다</li>
 *     <li>동시성: 시작 신호(CountDownLatch)로 같은 시각에 보내고 sleep으로 순서를 가정하지 않는다. 50회 반복</li>
 *     <li>모든 테스트 뒤 정합성 검사 API가 consistent=true</li>
 * </ul>
 * 미수·충전 원천 계정은 클래스 안에서 공유되므로 절대값이 아니라 테스트 전후 차이로 확인한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.flyway.default-schema=ledger_service",
        "spring.flyway.schemas=ledger_service",
        "spring.datasource.hikari.data-source-properties.ApplicationName=" + LedgerLockProbe.APP_NAME
})
class LedgerChargeReversalIntegrationTest {

    private static final String SCHEMA = "ledger_service";
    private static final String CHARGE_REVERSALS = LedgerTestClient.TRANSACTIONS + "/charge-reversals";
    private static final String INTEGRITY = "/internal/v1/ledger/integrity";
    private static final long CHARGE_AMOUNT = 100_000;
    private static final long SPENT = 80_000;
    private static final int REPEAT = 50;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

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
    private String testName;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        client = new LedgerTestClient(port, jsonMapper);
        testName = testInfo.getDisplayName();
    }

    @AfterEach
    void 정합성_검사가_일치한다() throws Exception {
        HttpResponse<String> response = client.get(INTEGRITY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(client.data(response).get("consistent").asBoolean(), testName + "\n" + response.body());
    }

    // ---- 금액 ----

    @Test
    void 잔액이_충분하면_원금액을_모두_회수하고_미수_항목이_없다() throws Exception {
        UUID walletId = client.openWallet();
        long sourceBefore = systemBalance("CHARGE_SOURCE");
        long receivableBefore = systemBalance("RECEIVABLE");
        UUID chargeId = UUID.randomUUID();
        UUID originalId = charge(chargeId, walletId, CHARGE_AMOUNT);

        HttpResponse<String> response = reverse(chargeId);

        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals("POSTED", data.get("status").asString());
        assertEquals("CHARGE_REVERSAL", data.get("referenceType").asString());
        assertEquals(chargeId.toString(), data.get("referenceId").asString());
        assertEquals(CHARGE_AMOUNT, data.get("recoveredAmount").asLong());
        assertEquals(0, data.get("receivableAmount").asLong());
        assertEquals(Map.of("CHARGE_REVERSAL_OUT", CHARGE_AMOUNT, "CHARGE_REVERSAL_IN", CHARGE_AMOUNT),
                entryAmounts(chargeId));
        assertEquals(0, walletBalance(walletId), "지갑이 원거래 전 상태로");
        assertEquals(sourceBefore, systemBalance("CHARGE_SOURCE"), "충전 원천이 원거래 전 상태로");
        assertEquals(receivableBefore, systemBalance("RECEIVABLE"));
        assertReversalLinks(chargeId, originalId, walletId);
    }

    @Test
    void 잔액이_부족하면_남은_잔액만_회수하고_부족분은_미수로_남는다() throws Exception {
        UUID walletId = client.openWallet();
        UUID otherWallet = client.openWallet();
        long sourceBefore = systemBalance("CHARGE_SOURCE");
        long receivableBefore = systemBalance("RECEIVABLE");
        UUID chargeId = UUID.randomUUID();
        UUID originalId = charge(chargeId, walletId, CHARGE_AMOUNT);
        HttpResponse<String> spend = client.register(transferBody(UUID.randomUUID(), walletId, otherWallet, SPENT));
        assertEquals(201, spend.statusCode(), spend.body());

        HttpResponse<String> response = reverse(chargeId);

        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals(CHARGE_AMOUNT - SPENT, data.get("recoveredAmount").asLong());
        assertEquals(SPENT, data.get("receivableAmount").asLong());
        assertEquals(Map.of("CHARGE_REVERSAL_OUT", CHARGE_AMOUNT - SPENT,
                        "CHARGE_REVERSAL_RECEIVABLE", SPENT,
                        "CHARGE_REVERSAL_IN", CHARGE_AMOUNT),
                entryAmounts(chargeId));
        assertEquals(0, walletBalance(walletId));
        assertEquals(sourceBefore, systemBalance("CHARGE_SOURCE"), "충전 원천 원금액 복구");
        assertEquals(receivableBefore - SPENT, systemBalance("RECEIVABLE"), "미수 잔액 -8만");
        assertReversalLinks(chargeId, originalId, walletId);
    }

    @Test
    void 잔액이_0이면_지갑_항목_없이_전액_미수다() throws Exception {
        UUID walletId = client.openWallet();
        UUID otherWallet = client.openWallet();
        long receivableBefore = systemBalance("RECEIVABLE");
        UUID chargeId = UUID.randomUUID();
        charge(chargeId, walletId, CHARGE_AMOUNT);
        HttpResponse<String> spend = client.register(
                transferBody(UUID.randomUUID(), walletId, otherWallet, CHARGE_AMOUNT));
        assertEquals(201, spend.statusCode(), spend.body());

        HttpResponse<String> response = reverse(chargeId);

        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = client.data(response);
        assertEquals(0, data.get("recoveredAmount").asLong());
        assertEquals(CHARGE_AMOUNT, data.get("receivableAmount").asLong());
        assertEquals(Map.of("CHARGE_REVERSAL_RECEIVABLE", CHARGE_AMOUNT, "CHARGE_REVERSAL_IN", CHARGE_AMOUNT),
                entryAmounts(chargeId));
        assertEquals(0, walletBalance(walletId));
        assertEquals(receivableBefore - CHARGE_AMOUNT, systemBalance("RECEIVABLE"));
    }

    // ---- 거절 (아무 행도 남기지 않음) ----

    @Test
    void 원거래가_없으면_404이고_아무것도_기록하지_않는다() throws Exception {
        Snapshot before = snapshot();

        HttpResponse<String> response = reverse(UUID.randomUUID());

        assertEquals(404, response.statusCode(), response.body());
        assertEquals("LEDGER_TRANSACTION_NOT_FOUND", client.errorCode(response.body()));
        assertEquals(before, snapshot());
    }

    @Test
    void 원거래가_REJECTED면_422이고_아무것도_기록하지_않는다() throws Exception {
        UUID chargeId = UUID.randomUUID();
        HttpResponse<String> cancelled = client.cancel(LedgerTestClient.cancelBody("CHARGE", chargeId));
        assertEquals(201, cancelled.statusCode(), cancelled.body());
        Snapshot before = snapshot();

        HttpResponse<String> response = reverse(chargeId);

        assertEquals(422, response.statusCode(), response.body());
        assertEquals("LEDGER_ORIGINAL_NOT_REVERSIBLE", client.errorCode(response.body()));
        assertEquals(before, snapshot());
        assertEquals(0, reversalRows(chargeId));
    }

    @Test
    void 원거래_항목_구조가_충전과_다르면_422이고_아무것도_기록하지_않는다() throws Exception {
        UUID walletId = client.openWallet();
        UUID chargeId = UUID.randomUUID();
        // 충전 원천 쪽도 CHARGE_IN인 거래 (기대 구조: 충전 원천 CHARGE_OUT + 지갑 CHARGE_IN)
        HttpResponse<String> registered = client.register(chargeBody(chargeId, walletId, CHARGE_AMOUNT));
        assertEquals(201, registered.statusCode(), registered.body());
        Snapshot before = snapshot();

        HttpResponse<String> response = reverse(chargeId);

        assertEquals(422, response.statusCode(), response.body());
        assertEquals("LEDGER_ORIGINAL_NOT_REVERSIBLE", client.errorCode(response.body()));
        assertEquals(before, snapshot());
        assertEquals(0, reversalRows(chargeId));
        assertEquals(CHARGE_AMOUNT, walletBalance(walletId));
    }

    // ---- 재요청 ----

    @Test
    void 같은_요청을_다시_보내면_200이고_같은_거래이며_거래는_1건이다() throws Exception {
        UUID walletId = client.openWallet();
        UUID chargeId = UUID.randomUUID();
        charge(chargeId, walletId, CHARGE_AMOUNT);
        HttpResponse<String> first = reverse(chargeId);
        assertEquals(201, first.statusCode(), first.body());
        long walletAfterFirst = walletBalance(walletId);
        long sourceAfterFirst = systemBalance("CHARGE_SOURCE");

        HttpResponse<String> retry = reverse(chargeId);

        assertEquals(200, retry.statusCode(), retry.body());
        JsonNode firstData = client.data(first);
        JsonNode retryData = client.data(retry);
        assertEquals(firstData.get("transactionId").asString(), retryData.get("transactionId").asString());
        assertEquals(firstData.get("recoveredAmount").asLong(), retryData.get("recoveredAmount").asLong());
        assertEquals(firstData.get("receivableAmount").asLong(), retryData.get("receivableAmount").asLong());
        assertEquals(firstData.get("entries"), retryData.get("entries"));
        assertEquals(1, reversalRows(chargeId));
        assertEquals(walletAfterFirst, walletBalance(walletId));
        assertEquals(sourceAfterFirst, systemBalance("CHARGE_SOURCE"));
    }

    // ---- 동시성 ----

    @Test
    void 같은_chargeId_동시_2건은_매회_거래_1건이고_두_응답이_같은_거래다(CapturedOutput output) throws Exception {
        List<String> problems = new ArrayList<>();
        for (int round = 0; round < REPEAT; round++) {
            UUID walletId = client.openWallet();
            UUID chargeId = UUID.randomUUID();
            charge(chargeId, walletId, CHARGE_AMOUNT);

            List<Call> calls = sendConcurrently(List.of(
                    new Request(CHARGE_REVERSALS, reversalBody(chargeId)),
                    new Request(CHARGE_REVERSALS, reversalBody(chargeId))));

            Set<Integer> statuses = new HashSet<>(List.of(calls.get(0).status(), calls.get(1).status()));
            boolean sameTransaction = calls.stream().allMatch(call -> call.status() / 100 == 2)
                    && client.data(calls.get(0).body()).get("transactionId").asString()
                    .equals(client.data(calls.get(1).body()).get("transactionId").asString());
            if (!statuses.equals(Set.of(201, 200)) || !sameTransaction || reversalRows(chargeId) != 1) {
                problems.add("round=" + round + " rows=" + reversalRows(chargeId) + "\n" + describe(calls));
            }
        }
        assertEquals(List.of(), problems, LedgerSameReferenceIntegrationTest.appLog(output));
    }

    @Test
    void 같은_지갑의_출금과_동시에_취소_분개해도_잔액_음수가_없고_금액이_맞는다(CapturedOutput output) throws Exception {
        List<String> problems = new ArrayList<>();
        int reversalFirst = 0;
        for (int round = 0; round < REPEAT; round++) {
            UUID walletId = client.openWallet();
            UUID otherWallet = client.openWallet();
            UUID chargeId = UUID.randomUUID();
            charge(chargeId, walletId, CHARGE_AMOUNT);
            long startBalance = walletBalance(walletId);
            UUID transferId = UUID.randomUUID();

            List<Call> calls = sendConcurrently(List.of(
                    new Request(CHARGE_REVERSALS, reversalBody(chargeId)),
                    new Request(LedgerTestClient.TRANSACTIONS,
                            transferBody(transferId, walletId, otherWallet, SPENT))));

            Call reversal = calls.get(0);
            Call transfer = calls.get(1);
            long walletAfter = walletBalance(walletId);
            String context = "round=" + round + " start=" + startBalance + " walletAfter=" + walletAfter + "\n"
                    + describe(calls);
            if (reversal.status() != 201) {
                problems.add("취소 분개 201 아님 " + context);
                continue;
            }
            JsonNode data = client.data(reversal.body());
            long recovered = data.get("recoveredAmount").asLong();
            long receivable = data.get("receivableAmount").asLong();
            long transferred;
            if (transfer.status() == 201) {
                transferred = SPENT;
            } else if (transfer.status() == 422
                    && "LEDGER_REJECTED_INSUFFICIENT_BALANCE".equals(client.errorCode(transfer.body()))) {
                transferred = 0;
                reversalFirst++;
            } else {
                problems.add("출금 응답이 201·잔액 부족 422가 아님 " + context);
                continue;
            }
            if (walletAfter < 0) {
                problems.add("지갑 잔액 음수 " + context);
            }
            if (recovered + transferred > startBalance) {
                problems.add("회수액 + 출금액 > 시작 잔액 " + context);
            }
            if (recovered + receivable != CHARGE_AMOUNT) {
                problems.add("회수액 + 미수 ≠ 원금액 " + context);
            }
            if (walletAfter != startBalance - recovered - transferred) {
                problems.add("지갑 잔액이 시작 잔액 - 회수액 - 출금액과 다름 " + context);
            }
            if (!debitEqualsCredit(chargeId)) {
                problems.add("취소 분개 차변 ≠ 대변 " + context);
            }
        }
        System.out.println("[charge-reversal] " + testName + " rounds=" + REPEAT + " reversalFirst=" + reversalFirst
                + " transferFirst=" + (REPEAT - reversalFirst) + " (기록만)");
        assertEquals(List.of(), problems, LedgerSameReferenceIntegrationTest.appLog(output));
    }

    @Test
    void 지갑_계정_잠금을_쥔_동안_취소_분개는_잠금_대기에_들어가고_풀면_끝난다(CapturedOutput output) throws Exception {
        UUID walletId = client.openWallet();
        UUID chargeId = UUID.randomUUID();
        charge(chargeId, walletId, CHARGE_AMOUNT);

        List<Call> calls;
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.holdAccount(walletAccountId(walletId));
            probe.startSampling();
            LedgerTestClient.Pending pending = client.sendConcurrently(CHARGE_REVERSALS,
                    List.of(reversalBody(chargeId)));
            try {
                List<LedgerLockProbe.Sample> waiting = probe.awaitWaiting(1, WAIT_TIMEOUT);
                assertEquals(0, reversalRows(chargeId), "잠금 대기 중에는 기록되지 않는다");
                System.out.println(probe.releaseInTime(holder, waiting).describe(testName));
            } finally {
                probe.release(holder);
                calls = pending.await(RESPONSE_TIMEOUT);
            }
            String context = describe(calls) + "\n" + probe.describeSamples() + "\n"
                    + LedgerSameReferenceIntegrationTest.appLog(output);
            assertEquals(201, calls.getFirst().status(), context);
        }
        assertEquals(1, reversalRows(chargeId));
        assertEquals(0, walletBalance(walletId));
    }

    // ---- 잠금 순서 강제 (지갑 잠금을 쥔 채 대기 순서를 pg_stat_activity로 맞춘 뒤 해제) ----

    @Test
    void 취소_분개가_먼저_대기하면_원금액을_회수하고_뒤_출금은_잔액_부족으로_거절된다(CapturedOutput output)
            throws Exception {
        runInLockOrder(true, output);
    }

    @Test
    void 출금이_먼저_대기하면_출금_뒤_잔액만_회수하고_나머지는_미수다(CapturedOutput output) throws Exception {
        runInLockOrder(false, output);
    }

    /**
     * 지갑 계정 잠금을 쥔 채 첫 요청이 잠금 대기에 들어간 것을 확인한 뒤 둘째 요청을 보내고, 둘 다 대기면 푼다.
     * 대기 순서를 의도대로 맞추지만 실제 반영 순서는 가정하지 않고 취소 분개 지갑 항목의 balance_after로 판별한다
     * (회수 전 잔액 = balance_after + 회수액이 시작 잔액이면 취소 분개 먼저, 시작 잔액 − 출금액이면 출금 먼저).
     * 시작 잔액 = 원금액 10만, 출금 5만: 취소 분개가 먼저면 잔액 0이라 출금은 잔액 부족
     */
    private void runInLockOrder(boolean reversalFirst, CapturedOutput output) throws Exception {
        long transferAmount = 50_000;
        UUID walletId = client.openWallet();
        UUID otherWallet = client.openWallet();
        UUID chargeId = UUID.randomUUID();
        charge(chargeId, walletId, CHARGE_AMOUNT);
        long startBalance = walletBalance(walletId);
        UUID transferId = UUID.randomUUID();
        Map<String, Object> transfer = transferBody(transferId, walletId, otherWallet, transferAmount);

        Call reversalCall;
        Call transferCall;
        String probeLog;
        try (LedgerLockProbe probe = new LedgerLockProbe(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Connection holder = probe.holdAccount(walletAccountId(walletId));
            probe.startSampling();
            LedgerTestClient.Pending first = null;
            LedgerTestClient.Pending second = null;
            List<Call> firstCalls;
            List<Call> secondCalls;
            try {
                first = reversalFirst
                        ? client.sendConcurrently(CHARGE_REVERSALS, List.of(reversalBody(chargeId)))
                        : client.sendConcurrently(LedgerTestClient.TRANSACTIONS, List.of(transfer));
                probe.awaitWaiting(1, WAIT_TIMEOUT);
                second = reversalFirst
                        ? client.sendConcurrently(LedgerTestClient.TRANSACTIONS, List.of(transfer))
                        : client.sendConcurrently(CHARGE_REVERSALS, List.of(reversalBody(chargeId)));
                List<LedgerLockProbe.Sample> waiting = probe.awaitWaiting(2, WAIT_TIMEOUT);
                System.out.println(probe.releaseInTime(holder, waiting).describe(testName));
            } finally {
                probe.release(holder);
                firstCalls = first == null ? List.of() : first.await(RESPONSE_TIMEOUT);
                secondCalls = second == null ? List.of() : second.await(RESPONSE_TIMEOUT);
            }
            reversalCall = (reversalFirst ? firstCalls : secondCalls).getFirst();
            transferCall = (reversalFirst ? secondCalls : firstCalls).getFirst();
            probeLog = probe.describeSamples();
        }
        String context = describe(List.of(reversalCall, transferCall)) + "\n" + probeLog + "\n"
                + LedgerSameReferenceIntegrationTest.appLog(output);

        assertEquals(201, reversalCall.status(), context);
        JsonNode data = client.data(reversalCall.body());
        long recovered = data.get("recoveredAmount").asLong();
        long receivable = data.get("receivableAmount").asLong();

        // 실제 반영 순서: 취소 분개가 잠근 시점의 지갑 잔액(회수 전 잔액)으로 판별한다
        long balanceAtReversal = recovered == 0 ? 0 : reversalWalletBalanceAfter(chargeId) + recovered;
        boolean actualReversalFirst;
        if (balanceAtReversal == startBalance) {
            actualReversalFirst = true;
        } else if (balanceAtReversal == startBalance - transferAmount) {
            actualReversalFirst = false;
        } else {
            throw new AssertionError("회수 전 잔액이 두 순서 어느 쪽과도 맞지 않음: " + balanceAtReversal + "\n" + context);
        }
        System.out.println("[charge-reversal-order] " + testName + " intended="
                + (reversalFirst ? "REVERSAL_FIRST" : "TRANSFER_FIRST") + " actual="
                + (actualReversalFirst ? "REVERSAL_FIRST" : "TRANSFER_FIRST")
                + " balanceAtReversal=" + balanceAtReversal + " recovered=" + recovered + " receivable=" + receivable
                + " transferStatus=" + transferCall.status());

        if (actualReversalFirst) {
            assertEquals(startBalance, recovered, context);
            assertEquals(0, receivable, context);
            assertEquals(422, transferCall.status(), context);
            assertEquals("LEDGER_REJECTED_INSUFFICIENT_BALANCE", client.errorCode(transferCall.body()), context);
            assertEquals("REJECTED", transferStatus(transferId), context);
            assertEquals(0, transferEntryRows(transferId), context);
            assertEquals(0, walletBalance(otherWallet), context);
        } else {
            assertEquals(201, transferCall.status(), context);
            assertEquals("POSTED", transferStatus(transferId), context);
            assertEquals(startBalance - transferAmount, recovered, context);
            assertEquals(CHARGE_AMOUNT - recovered, receivable, context);
            assertEquals(transferAmount, walletBalance(otherWallet), context);
        }
        long walletAfter = walletBalance(walletId);
        assertTrue(walletAfter >= 0, "지갑 잔액 음수 없음\n" + context);
        assertEquals(0, walletAfter, context);
        assertEquals(CHARGE_AMOUNT, recovered + receivable, context);
        assertTrue(debitEqualsCredit(chargeId), "취소 분개 차변 = 대변\n" + context);
        assertTrue(transferDebitEqualsCredit(transferId), "출금 차변 = 대변\n" + context);
        // 정합성 검사 consistent=true는 @AfterEach에서 확인
    }

    /** 취소 분개 지갑 항목(CHARGE_REVERSAL_OUT)의 반영 후 잔액 */
    private long reversalWalletBalanceAfter(UUID chargeId) {
        return jdbcTemplate.queryForObject("""
                SELECT e.balance_after FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = 'CHARGE_REVERSAL' AND t.reference_id = ?
                   AND e.entry_type = 'CHARGE_REVERSAL_OUT'
                """, Long.class, chargeId);
    }

    private String transferStatus(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transactions WHERE reference_type = 'TRANSFER' AND reference_id = ?",
                String.class, transferId);
    }

    private int transferEntryRows(UUID transferId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = 'TRANSFER' AND t.reference_id = ?
                """, Integer.class, transferId);
    }

    private boolean transferDebitEqualsCredit(UUID transferId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0)
                     = coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                  FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = 'TRANSFER' AND t.reference_id = ?
                """, Boolean.class, transferId));
    }

    // ---- 도구 ----

    /** 실제 충전 요청과 같은 구조: 충전 원천 DEBIT CHARGE_OUT + 지갑 CREDIT CHARGE_IN. 원거래 ID를 돌려준다 */
    private UUID charge(UUID chargeId, UUID walletId, long amount) throws Exception {
        HttpResponse<String> response = client.register(body("CHARGE", chargeId, List.of(
                system("CHARGE_SOURCE", "KRW", "DEBIT", amount, "CHARGE_OUT"),
                user(walletId, "KRW", "CREDIT", amount, "CHARGE_IN"))));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(client.data(response).get("transactionId").asString());
    }

    private static Map<String, Object> reversalBody(UUID chargeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chargeId", chargeId.toString());
        body.put("description", "이중 지급 바로잡기");
        return body;
    }

    private HttpResponse<String> reverse(UUID chargeId) throws Exception {
        return client.post(CHARGE_REVERSALS, jsonMapper.writeValueAsString(reversalBody(chargeId)));
    }

    private record Request(String path, Map<String, Object> body) {
    }

    /** 경로가 다른 요청들을 시작 신호에 맞춰 동시에 보낸다. 응답은 요청 순서대로 */
    private List<Call> sendConcurrently(List<Request> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch ready = new CountDownLatch(requests.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Call>> futures = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                int index = i;
                String path = requests.get(i).path();
                String json = jsonMapper.writeValueAsString(requests.get(i).body());
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    Instant sentAt = Instant.now();
                    try {
                        HttpResponse<String> response = client.post(path, json);
                        return new Call(index, sentAt, Instant.now(), response.statusCode(), response.body(), null);
                    } catch (Exception e) {
                        return new Call(index, sentAt, Instant.now(), -1, null, e.getClass().getName());
                    }
                }));
            }
            assertTrue(ready.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "요청 스레드 준비 시간 초과");
            start.countDown();
            List<Call> calls = new ArrayList<>();
            for (Future<Call> future : futures) {
                calls.add(future.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return calls;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 취소 분개 거래의 원거래·userId·항목 계정 연결 확인 */
    private void assertReversalLinks(UUID chargeId, UUID originalId, UUID walletId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT r.original_transaction_id, r.user_id, o.user_id AS original_user_id, r.status
                  FROM ledger_transactions r
                  JOIN ledger_transactions o ON o.reference_type = 'CHARGE' AND o.reference_id = r.reference_id
                 WHERE r.reference_type = 'CHARGE_REVERSAL' AND r.reference_id = ?
                """, chargeId);
        assertEquals(originalId, row.get("original_transaction_id"));
        assertEquals(row.get("original_user_id"), row.get("user_id"));
        assertEquals("POSTED", row.get("status"));
        List<Map<String, Object>> walletEntries = jdbcTemplate.queryForList("""
                SELECT a.wallet_id FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE t.reference_type = 'CHARGE_REVERSAL' AND t.reference_id = ?
                   AND e.entry_type = 'CHARGE_REVERSAL_OUT'
                """, chargeId);
        for (Map<String, Object> entry : walletEntries) {
            assertEquals(walletId, entry.get("wallet_id"));
        }
    }

    /** 취소 분개 항목 유형별 금액 */
    private Map<String, Long> entryAmounts(UUID chargeId) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT e.entry_type, e.amount FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = 'CHARGE_REVERSAL' AND t.reference_id = ?
                """, resultSet -> {
            amounts.put(resultSet.getString(1), resultSet.getLong(2));
        }, chargeId);
        return amounts;
    }

    private boolean debitEqualsCredit(UUID chargeId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0)
                     = coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                  FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.reference_type = 'CHARGE_REVERSAL' AND t.reference_id = ?
                """, Boolean.class, chargeId));
    }

    private int reversalRows(UUID chargeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference_type = 'CHARGE_REVERSAL' AND reference_id = ?",
                Integer.class, chargeId);
    }

    private long walletBalance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", Long.class, walletId);
    }

    private UUID walletAccountId(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_accounts WHERE wallet_id = ? AND currency = 'KRW'", UUID.class, walletId);
    }

    private long systemBalance(String accountType) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM ledger_accounts WHERE owner_type = 'SYSTEM' AND account_type = ? AND currency = 'KRW'",
                Long.class, accountType);
    }

    /** 거래·항목 행 수와 충전 원천·미수 잔액. 거절 시 아무것도 바뀌지 않았는지 비교한다 */
    private record Snapshot(long transactions, long entries, long chargeSource, long receivable) {
    }

    private Snapshot snapshot() {
        return new Snapshot(
                jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class),
                jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Long.class),
                systemBalance("CHARGE_SOURCE"), systemBalance("RECEIVABLE"));
    }
}
