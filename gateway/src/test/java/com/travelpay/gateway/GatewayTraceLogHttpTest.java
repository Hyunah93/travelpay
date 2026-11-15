package com.travelpay.gateway;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 설정이나 구현을 바꿔도 요청 로그·호출별 span·동시 요청의 추적은 보존되어야 합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = {"--spring.config.name=application-prod", "--spring.profiles.active=prod"}, properties = {
                "eureka.client.enabled=false",
                "management.tracing.sampling.probability=0.0",
                "travelpay.gateway.session-verification.protection.minimum-calls=2",
                "travelpay.gateway.session-verification.protection.open-duration=30s", "logging.level.root=WARN"
        })
class GatewayTraceLogHttpTest {

    private static final String SESSION = "/internal/v1/auth/session";
    private static final String WALLET_PATH = "/api/v1/wallets/me";
    private static final String CLIENT_LOGGER = "com.travelpay.gateway.auth.SessionVerificationClient";
    private static final String CIRCUIT_LOGGER = "com.travelpay.gateway.auth.SessionVerificationConfiguration";
    private static final StubServer USER = StubServer.start();
    private static final StubServer WALLET = StubServer.start();
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    };

    @Value("${local.server.port}") private int port;
    @Autowired private CircuitBreaker circuitBreaker;

    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri", WALLET::uri);
    }

    @BeforeEach
    void prepare() {
        circuitBreaker.reset(); USER.reset(); WALLET.reset(); events.clear();
        appender.setContext(rootLogger().getLoggerContext()); appender.start(); rootLogger().addAppender(appender);
    }

    @AfterEach void release() { rootLogger().detachAppender(appender); appender.stop(); }
    @AfterAll static void stop() { USER.close(); WALLET.close(); }

    @ParameterizedTest
    @CsvSource({"418,{}", "200,{}", "200,not-json", "204,''"})
    void sessionContractErrorLogsKeepTheFailedRequestTrace(int status, String body) {
        USER.reply(SESSION, Reply.json(status, body));

        client().get().uri(WALLET_PATH).header("Cookie", "SESSION=test-only")
                .exchange().expectStatus().isEqualTo(503);

        var expected = trace(USER.received(SESSION).getFirst().header("traceparent"));
        var errors = from(CLIENT_LOGGER);
        assertFalse(errors.isEmpty());
        errors.forEach(event -> assertEquals(expected, event.getMDCPropertyMap().get("traceId"),
                event.getFormattedMessage()));
        assertTrue(WALLET.received().isEmpty());
    }

    @Test
    void requestTriggeredCircuitTransitionKeepsTheTriggeringTrace() {
        USER.reply(SESSION, Reply.json(503, "{}"));
        for (int i = 0; i < 2; i++) {
            client().get().uri(WALLET_PATH).header("Cookie", "SESSION=test-only")
                    .exchange().expectStatus().isEqualTo(503);
        }
        var expected = trace(USER.received(SESSION).getLast().header("traceparent"));
        // 다른 테스트 컨텍스트의 예약된 OPEN→HALF_OPEN 로그와 요청이 연 CLOSED→OPEN을 구분합니다.
        var transitions = from(CIRCUIT_LOGGER).stream()
                .filter(event -> event.getArgumentArray() != null && event.getArgumentArray().length > 0
                        && event.getArgumentArray()[0] == CircuitBreaker.StateTransition.CLOSED_TO_OPEN)
                .toList();
        assertEquals(1, transitions.size());
        transitions.forEach(event -> assertEquals(expected, event.getMDCPropertyMap().get("traceId")));
    }

    @Test
    void successfulCallsKeepOneTraceAndSeparateClientSpans() {
        USER.reply(SESSION, Reply.json(200, """
                {"success":true,"data":{"userId":"550e8400-e29b-41d4-a716-446655440000","role":"USER"},"error":null}
                """));
        client().get().uri(WALLET_PATH).header("Cookie", "SESSION=test-only").exchange().expectStatus().isOk();

        var verification = USER.received(SESSION).getFirst().header("traceparent").getFirst().split("-");
        var business = WALLET.received(WALLET_PATH).getFirst().header("traceparent").getFirst().split("-");
        assertEquals(verification[1], business[1]);
        assertNotEquals(verification[2], business[2]);
    }

    @Test
    void concurrentErrorsKeepEachTraceAndDoNotMixRequests() {
        USER.replyToRequest(SESSION, request -> Reply.json(418 + requestIndex(request), "{}"));
        // 다른 요청의 실패로 서킷이 먼저 열리지 않게, 이 시험에서는 실패 판정만 제외합니다.
        circuitBreaker.transitionToDisabledState(); events.clear();
        var http = WebClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        var statuses = Flux.fromIterable(IntStream.range(0, 12).boxed().toList())
                .flatMap(index -> http.get().uri(WALLET_PATH).header("Cookie", "SESSION=test-" + index)
                        .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value())), 6)
                .collectList().block(Duration.ofSeconds(20));

        assertEquals(12, statuses.size()); assertTrue(statuses.stream().allMatch(status -> status == 503));
        // trace 집합만 비교하면 두 요청의 trace가 서로 뒤바뀐 경우를 놓칩니다. 요청별 응답 상태와 짝지어 비교합니다.
        var expected = USER.received(SESSION).stream().collect(Collectors.toMap(
                request -> 418 + requestIndex(request), request -> trace(request.header("traceparent"))));
        var actual = from(CLIENT_LOGGER).stream().collect(Collectors.toMap(
                event -> (Integer) event.getArgumentArray()[0], event -> {
                    var traceId = event.getMDCPropertyMap().get("traceId");
                    assertTrue(traceId != null && !traceId.isBlank());
                    return traceId;
                }));
        assertEquals(12, expected.size());
        assertEquals(12, expected.values().stream().distinct().count());
        assertEquals(expected, actual);
    }

    private static int requestIndex(StubServer.Received request) {
        return Integer.parseInt(request.header("Cookie").getFirst().substring("SESSION=test-".length()));
    }

    private static String trace(List<String> headers) {
        assertEquals(1, headers.size()); return headers.getFirst().split("-")[1];
    }
    private List<ILoggingEvent> from(String name) { return events.stream().filter(e -> e.getLoggerName().equals(name)).toList(); }
    private static Logger rootLogger() { return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME); }
    private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(10)).build(); }
}
