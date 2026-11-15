package com.travelpay.exchange;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원장이 느리거나 응답하지 않을 때 서킷이 열려 환전 신청이 원장을 기다리지 않고, 원장이 회복되면 복구 스케줄러가
 * 쌓인 주문을 확정하며 서킷이 닫히는지 실제 원장 클라이언트(Feign + LedgerClientAdapter)로 확인합니다(#282).
 *
 * 원장 자리에는 응답을 늦출 수 있는 테스트용 HTTP 서버를 띄웁니다. 기다리는 시간을 줄이려고 서킷 기준을 작게 둡니다.
 * 최근 2건 이상 중 절반이 결과 모름이거나 150ms 이상 걸리면 열고, 1초 뒤 2건을 시험으로 보냅니다.
 * 원장 응답 대기 한도는 1초로 줄입니다. 지갑 서비스는 고정된 지갑을 돌려주는 Reader로 바꿉니다. Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "exchange.rate.koreaexim.api-key=integration-test",
        "exchange.order.recovery.fixed-delay=1h",
        "spring.cloud.openfeign.client.config.ledger-service.read-timeout=1000",
        "exchange.ledger.circuit-breaker.minimum-calls=2",
        "exchange.ledger.circuit-breaker.slow-call-duration=150ms",
        "exchange.ledger.circuit-breaker.wait-in-open=1s",
        "exchange.ledger.circuit-breaker.half-open-calls=2"
})
class ExchangeOrderLedgerCircuitIntegrationTest {

    private static final String SCHEMA = "exchange_service";
    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WALLET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TX_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String POSTED_BODY = """
            {"success":true,"data":{"transactionId":"%s","status":"POSTED"},"error":null}
            """.formatted(TX_ID);
    /** 원장 응답 대기 한도(1초)를 넘겨 결과 모름이 되는 지연 */
    private static final long TIMEOUT_DELAY_MS = 1_500;
    /** 성공은 하지만 느린 호출 기준(150ms)을 넘는 지연. 개선 전 측정의 원장 3초 상황을 줄인 것 */
    private static final long SLOW_DELAY_MS = 300;
    /** 서킷이 열린 뒤 반열림으로 바뀌기를 기다리는 최대 시간(wait-in-open 1초에 여유를 둠) */
    private static final Duration HALF_OPEN_WAIT_LIMIT = Duration.ofSeconds(5);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    private static final HttpServer LEDGER = startLedger();
    private static final AtomicInteger LEDGER_CALLS = new AtomicInteger();
    private static volatile long ledgerDelayMs;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        TestRedis.register(registry);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.ledger.url", () -> "http://127.0.0.1:" + LEDGER.getAddress().getPort());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ExchangeRateRepository rateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeQuoteIntegrationTest.MutableClock clock;

    @Autowired
    private ExchangeOrderRecoveryService recoveryService;

    @Autowired
    private CircuitBreaker ledgerCircuit;

    private MockMvc mockMvc;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        ledgerCircuit.reset();
        LEDGER_CALLS.set(0);
        ledgerDelayMs = 0;
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".exchange_reservations, " + SCHEMA + ".exchange_order_status_histories, "
                + SCHEMA + ".exchange_orders, " + SCHEMA + ".exchange_quotes, " + SCHEMA + ".exchange_rates");
        clock.set(NOW);
        BigDecimal mid = new BigDecimal("838.30");
        rateRepository.save(ExchangeRate.collected(new CollectedRate(ForeignCurrency.JPY, 100, mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                LocalDate.of(2026, 11, 11), RateSource.KOREAEXIM), NOW.minus(Duration.ofHours(1))));
    }

    @AfterAll
    static void stopLedger() {
        LEDGER.stop(0);
    }

    /**
     * 원장이 응답하지 않으면 시간 초과 두 건 뒤 서킷이 열리고, 그다음 신청은 원장에 보내지 않고 바로 처리 중(202)으로 답한다.
     * 원장이 회복된 뒤 복구가 시험 호출로 서킷을 닫고, 쌓인 주문을 모두 완료한다.
     */
    @Test
    void timeoutsOpenCircuitAndRecoveryClosesItAfterLedgerRecovers() throws Exception {
        ledgerDelayMs = TIMEOUT_DELAY_MS;
        placeNewOrder().andExpect(status().isAccepted());
        placeNewOrder().andExpect(status().isAccepted());
        assertEquals(CircuitBreaker.State.OPEN, ledgerCircuit.getState());
        assertEquals(2, LEDGER_CALLS.get());

        placeNewOrder()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals(2, LEDGER_CALLS.get(), "서킷이 열린 동안에는 원장에 보내지 않는다");
        assertEquals(3, countOrders("PROCESSING"));

        // 원장 회복. 서킷이 시험 호출을 허락할 때까지 기다리고, 주문 대기 시간(30초 × 2)이 지난 시점에 복구한다
        ledgerDelayMs = 0;
        awaitHalfOpen();
        clock.set(NOW.plus(Duration.ofMinutes(2)));

        ExchangeOrderRecoveryService.RecoveryResult result = recoveryService.recover(10);

        assertEquals(3, result.settled());
        assertEquals(3, countOrders("COMPLETED"));
        assertEquals(CircuitBreaker.State.CLOSED, ledgerCircuit.getState());
    }

    /** 원장이 성공은 하지만 느리면(느린 호출 기준 초과) 서킷이 열려, 그다음 신청은 원장을 기다리지 않는다 */
    @Test
    void slowSuccessfulCallsOpenCircuit() throws Exception {
        ledgerDelayMs = SLOW_DELAY_MS;
        placeNewOrder()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        placeNewOrder().andExpect(status().isOk());
        assertEquals(CircuitBreaker.State.OPEN, ledgerCircuit.getState());

        placeNewOrder()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertEquals(2, LEDGER_CALLS.get(), "서킷이 열린 동안에는 원장에 보내지 않는다");
    }

    /** 원장이 시험 호출에도 응답하지 않으면 서킷은 다시 열리고, 주문은 처리 중으로 남아 다음 복구를 기다린다 */
    @Test
    void circuitReopensWhenLedgerIsStillDown() throws Exception {
        ledgerDelayMs = TIMEOUT_DELAY_MS;
        placeNewOrder().andExpect(status().isAccepted());
        placeNewOrder().andExpect(status().isAccepted());
        placeNewOrder().andExpect(status().isAccepted());
        assertEquals(CircuitBreaker.State.OPEN, ledgerCircuit.getState());

        awaitHalfOpen();
        clock.set(NOW.plus(Duration.ofMinutes(2)));

        ExchangeOrderRecoveryService.RecoveryResult result = recoveryService.recover(10);

        assertEquals(0, result.settled());
        assertEquals(3, countOrders("PROCESSING"));
        assertEquals(CircuitBreaker.State.OPEN, ledgerCircuit.getState());
        // 처음 두 건 + 시험 호출 두 건. 다시 열린 뒤 세 번째 주문은 보내지 않는다
        assertEquals(4, LEDGER_CALLS.get());
    }

    /**
     * 장애 중 헛걸음해 재시도 대기가 늘어난 주문(꼬리)도, 원장이 회복된 뒤 한 번의 복구 주기에 함께 확정된다(#317).
     * 1. 원장 장애 중 신청 3건 → 서킷 열림, 모두 PROCESSING(확인 횟수 1)
     * 2. 1분 뒤 원장이 아직 장애일 때 복구: 반열림 시험 호출 2건이 실패해 그 2건은 확인 횟수 2(다음 시도까지 2분)가 되고,
     *    서킷이 다시 열려 세 번째 주문은 선점하지 않는다(확인 횟수 1 그대로)
     * 3. 원장 회복 뒤(신청 2분 뒤) 복구: 대기가 끝난 세 번째 주문이 성공해 원장이 살아 있음을 확인하고,
     *    아직 대기 중인 꼬리 2건도 대기와 관계없이 이어서 처리한다
     */
    @Test
    void tailOrdersAreRecoveredInOneCycleOnceTheLedgerAnswers() throws Exception {
        ledgerDelayMs = TIMEOUT_DELAY_MS;
        placeNewOrder().andExpect(status().isAccepted());
        placeNewOrder().andExpect(status().isAccepted());
        placeNewOrder().andExpect(status().isAccepted());
        assertEquals(CircuitBreaker.State.OPEN, ledgerCircuit.getState());

        // 2. 원장은 아직 장애
        awaitHalfOpen();
        clock.set(NOW.plusSeconds(61));
        ExchangeOrderRecoveryService.RecoveryCycle duringOutage = recoveryService.recoverPending(50, Duration.ofSeconds(20));

        assertEquals(ExchangeOrderRecoveryService.EndReason.LEDGER_UNAVAILABLE, duringOutage.endedBy());
        assertEquals(Map.of(1, 1, 2, 2), checkCounts(), "시험 호출에 실패한 2건만 확인 횟수가 오르고, 서킷이 막은 1건은 그대로");

        // 3. 원장 회복. 꼬리 2건은 마지막 확인(+61초)에서 2분이 지나지 않아 아직 재시도 대기 중
        ledgerDelayMs = 0;
        awaitHalfOpen();
        clock.set(NOW.plusSeconds(122));
        ExchangeOrderRecoveryService.RecoveryCycle afterRecovery = recoveryService.recoverPending(50, Duration.ofSeconds(20));

        assertEquals(3, afterRecovery.settled());
        assertEquals(true, afterRecovery.backoffSkipped(), "꼬리 2건은 재시도 대기를 무시하고 처리됨");
        assertEquals(3, countOrders("COMPLETED"));
        assertEquals(CircuitBreaker.State.CLOSED, ledgerCircuit.getState());
        // 신청 때 2건(세 번째는 서킷이 막음) + 장애 중 시험 호출 2건 + 회복 뒤 3건
        assertEquals(7, LEDGER_CALLS.get());
    }

    /** 확인 횟수별 주문 수 */
    private Map<Integer, Integer> checkCounts() {
        Map<Integer, Integer> counts = new HashMap<>();
        jdbcTemplate.query("SELECT ledger_check_count, count(*) AS n FROM " + SCHEMA + ".exchange_orders GROUP BY ledger_check_count",
                rs -> {
                    counts.put(rs.getInt("ledger_check_count"), rs.getInt("n"));
                });
        return counts;
    }

    /**
     * 서킷이 열린 뒤 대기 시간(1초)이 지나 반열림으로 바뀔 때까지 기다린다. 전환은 서킷 내부 타이머가 하므로(#317, 호출 없이 반열림)
     * 고정 시간만 쉬면 타이머가 조금 늦을 때 아직 열림이라, 복구가 원장을 부르지 않고 건너뛰어 테스트가 흔들린다
     */
    private void awaitHalfOpen() throws InterruptedException {
        long deadline = System.nanoTime() + HALF_OPEN_WAIT_LIMIT.toNanos();
        while (ledgerCircuit.getState() == CircuitBreaker.State.OPEN) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("서킷이 반열림으로 바뀌지 않았습니다");
            }
            Thread.sleep(20);
        }
    }

    private int countOrders(String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".exchange_orders WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
    }

    /** 견적을 새로 받아 서로 다른 멱등 키로 환전을 신청합니다. */
    private ResultActions placeNewOrder() throws Exception {
        String quoteBody = mockMvc.perform(post("/api/v1/exchange-quotes")
                        .header("User-Id", OWNER)
                        .header("User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCurrency":"KRW","toCurrency":"JPY","amount":"100000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String quoteId = JsonPath.read(quoteBody, "$.data.quoteId");
        return mockMvc.perform(post("/api/v1/exchanges")
                .header("User-Id", OWNER)
                .header("User-Role", "USER")
                .header("Idempotency-Key", "circuit-key-" + (++orderSeq))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"quoteId":"%s","walletId":"%s"}
                        """.formatted(quoteId, WALLET_ID)));
    }

    /** 원장 거래 등록 경로에 정해 둔 지연 뒤 201 POSTED로 답하는 서버. 늦은 응답이 다음 요청을 막지 않게 요청마다 스레드를 쓴다 */
    private static HttpServer startLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/ledger/transactions", ExchangeOrderLedgerCircuitIntegrationTest::respond);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("테스트 원장 서버를 띄우지 못했습니다.", e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        LEDGER_CALLS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        long delay = ledgerDelayMs;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = POSTED_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException ignored) {
            // 클라이언트가 시간 초과로 먼저 끊은 응답
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        ExchangeQuoteIntegrationTest.MutableClock testClock() {
            return new ExchangeQuoteIntegrationTest.MutableClock();
        }

        /** 외부 API 대신 빈 목록을 돌려줍니다. 기동 시 수집이 아무것도 저장하지 않게 합니다. */
        @Bean
        @Primary
        ExchangeRateProvider testRateProvider() {
            return new ExchangeRateProvider() {
                @Override
                public RateSource source() {
                    return RateSource.KOREAEXIM;
                }

                @Override
                public List<CollectedRate> fetch(LocalDate date) {
                    return List.of();
                }
            };
        }

        /** 누가 조회하든 WALLET_ID인 ACTIVE 지갑(원화·엔화 계정)을 돌려줍니다. */
        @Bean
        @Primary
        WalletBalanceReader testWalletBalanceReader() {
            return userId -> Optional.of(new WalletBalances(WALLET_ID, true, Map.of(
                    Currency.KRW, new WalletBalances.Account(new Money(Currency.KRW, 500_000), true),
                    Currency.JPY, new WalletBalances.Account(new Money(Currency.JPY, 0), true))));
        }
    }
}
