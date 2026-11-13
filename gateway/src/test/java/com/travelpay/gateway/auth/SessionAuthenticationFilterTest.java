package com.travelpay.gateway.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuthenticationFilterTest {

    private static final String PATH = "/internal/v1/auth/session";
    private static final StubServer USER = StubServer.start();
    private static final String VERIFIED = """
            {"success":true,"data":{"userId":"550e8400-e29b-41d4-a716-446655440000","role":"USER"},"error":null}
            """;

    @AfterAll
    static void stop() {
        USER.close();
    }

    @ParameterizedTest
    @CsvSource({"401,COMMON_UNAUTHORIZED", "403,COMMON_CSRF_INVALID", "409,COMMON_CONFLICT"})
    void anAuthenticationRejectionReplacesAnExistingCachePolicyAndPreservesTheResponse(int status, String code) {
        var body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":[]}}
                """.formatted(code);
        USER.reply(PATH, Reply.json(status, body));
        var sessionClient = new SessionVerificationClient(WebClient.builder().build(), URI.create(USER.uri() + PATH));
        var filter = new SessionAuthenticationFilter(sessionClient, new SessionVerificationProperties(
                URI.create(USER.uri() + PATH), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)),
                CircuitBreaker.ofDefaults("test"), Bulkhead.ofDefaults("test"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me")
                .header(HttpHeaders.COOKIE, "SESSION=abc").build());
        exchange.getResponse().getHeaders().setCacheControl("public, max-age=600");

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("거절한 요청은 업무 서비스로 전달하면 안 됩니다.")))
                .block(Duration.ofSeconds(5));

        var response = exchange.getResponse();
        assertEquals(HttpStatusCode.valueOf(status), response.getStatusCode());
        assertEquals(List.of("no-store"), response.getHeaders().get(HttpHeaders.CACHE_CONTROL));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals(body, response.getBodyAsString().block(Duration.ofSeconds(5)));
        assertFalse(response.getHeaders().containsHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void aClientFilterWaitingBeforeTheConnectionIsCancelledAsAnAuthenticationOutage() {
        var cancelled = new AtomicBoolean();
        var webClient = WebClient.builder()
                .filter((request, next) -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)))
                .build();
        var uri = URI.create(USER.uri() + PATH);
        var sessionClient = new SessionVerificationClient(webClient, uri);
        var filter = new SessionAuthenticationFilter(sessionClient, new SessionVerificationProperties(
                uri, Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(150)),
                CircuitBreaker.ofDefaults("test"), Bulkhead.ofDefaults("test"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me").build());

        var exception = assertThrows(BusinessException.class, () -> filter.filter(exchange,
                ignored -> Mono.error(new AssertionError("검증이 끝나지 않은 요청은 전달하면 안 됩니다.")))
                .block(Duration.ofSeconds(2)));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        assertInstanceOf(TimeoutException.class, exception.getCause());
        assertTrue(cancelled.get());
    }

    @Test
    void aRequestRoutedToUserServiceIsPassedOnWithoutSessionVerificationOrMemberHeaders() {
        USER.reset();
        var exchange = routedTo("lb://user-service", MockServerHttpRequest.patch("/api/v1/users/me/password")
                .header(HttpHeaders.COOKIE, "SESSION=abc").build());
        var forwarded = new AtomicReference<ServerWebExchange>();

        filter().filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertTrue(USER.received().isEmpty());
        var request = forwarded.get().getRequest();
        assertEquals(List.of("SESSION=abc"), request.getHeaders().get(HttpHeaders.COOKIE));
        assertFalse(request.getHeaders().containsHeader("User-Id"));
        assertFalse(request.getHeaders().containsHeader("User-Role"));
    }

    // 목적지가 user-service가 아니거나 표기가 다르면 세션 확인 API를 거칩니다.
    @ParameterizedTest
    @CsvSource({"lb://wallet-service", "lb://user-service-v2", "http://user-service"})
    void aRequestRoutedElsewhereStillNeedsSessionVerification(String destination) {
        USER.reset();
        USER.reply(PATH, Reply.json(401, """
                {"success":false,"data":null,"error":{"code":"COMMON_UNAUTHORIZED","message":"m","details":[]}}
                """));
        var exchange = routedTo(destination, MockServerHttpRequest.get("/api/v1/wallets/me").build());

        filter().filter(exchange, ignored -> Mono.error(new AssertionError("거절한 요청은 전달하면 안 됩니다.")))
                .block(Duration.ofSeconds(5));

        assertEquals(1, USER.received(PATH).size());
        assertEquals(HttpStatusCode.valueOf(401), exchange.getResponse().getStatusCode());
    }

    @Test
    void anOpenCircuitAnswers503WithoutCallingSessionVerification() {
        USER.reset();
        USER.reply(PATH, Reply.json(500, ""));
        var circuitBreaker = circuitOpeningAfterTwoOutages();
        var filter = filter(circuitBreaker, Bulkhead.ofDefaults("test"));

        for (var call = 0; call < 2; call++) {
            var outage = assertThrows(BusinessException.class, () -> verify(filter).block(Duration.ofSeconds(5)));
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, outage.getErrorCode());
        }
        var rejected = assertThrows(BusinessException.class, () -> verify(filter).block(Duration.ofSeconds(5)));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, rejected.getErrorCode());
        assertInstanceOf(CallNotPermittedException.class, rejected.getCause());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, USER.received(PATH).size());
    }

    // 서킷이 전체 상한 바깥에 있어야 시간 초과가 취소가 아닌 장애로 세어짐
    @Test
    void verificationsCutByTheTotalLimitCountAsOutagesAndOpenTheCircuit() {
        USER.reset();
        USER.reply(PATH, Reply.json(200, VERIFIED).delayedBy(Duration.ofMillis(1000)));
        var circuitBreaker = circuitOpeningAfterTwoOutages();
        var uri = URI.create(USER.uri() + PATH);
        var filter = new SessionAuthenticationFilter(new SessionVerificationClient(WebClient.builder().build(), uri),
                new SessionVerificationProperties(uri, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofMillis(150)),
                circuitBreaker, Bulkhead.ofDefaults("test"));

        for (var call = 0; call < 2; call++) {
            var outage = assertThrows(BusinessException.class, () -> verify(filter).block(Duration.ofSeconds(5)));
            assertInstanceOf(TimeoutException.class, outage.getCause());
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    // 401·403·409 거절은 User가 정상적으로 판단한 결과라 서킷을 열지 않음
    @Test
    void rejectionsAreSuccessfulCallsThatKeepTheCircuitClosed() {
        USER.reset();
        USER.reply(PATH, Reply.json(401, """
                {"success":false,"data":null,"error":{"code":"COMMON_UNAUTHORIZED","message":"m","details":[]}}
                """));
        var circuitBreaker = circuitOpeningAfterTwoOutages();
        var filter = filter(circuitBreaker, Bulkhead.ofDefaults("test"));

        for (var call = 0; call < 5; call++) {
            verify(filter).block(Duration.ofSeconds(5));
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(5, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }

    // 동시 호출이 가득 차면 기다리지 않고 바로 503이며, 그 거절은 User 장애가 아니라 서킷 통계에 들지 않음
    @Test
    void aFullBulkheadAnswers503AtOnceWithoutCountingAgainstTheCircuit() throws InterruptedException {
        USER.reset();
        USER.reply(PATH, Reply.json(200, VERIFIED).delayedBy(Duration.ofMillis(800)));
        var circuitBreaker = circuitOpeningAfterTwoOutages();
        var bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        var filter = filter(circuitBreaker, bulkhead);
        var first = verify(filter).toFuture();
        awaitSessionVerificationRequest();

        var started = System.nanoTime();
        var rejected = assertThrows(BusinessException.class, () -> verify(filter).block(Duration.ofSeconds(5)));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        first.join();

        assertInstanceOf(BulkheadFullException.class, rejected.getCause());
        assertTrue(elapsed.compareTo(Duration.ofMillis(300)) < 0, elapsed.toString());
        assertEquals(1, USER.received(PATH).size());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(1, bulkhead.getMetrics().getAvailableConcurrentCalls());
    }

    // 브라우저가 연결을 끊어 확인이 취소돼도 허가가 새지 않고, 취소는 성공·실패 어느 쪽으로도 세지 않음
    @Test
    void aCancelledVerificationReturnsItsPermitsWithoutBeingCounted() throws InterruptedException {
        USER.reset();
        USER.reply(PATH, Reply.json(200, VERIFIED).delayedBy(Duration.ofMillis(800)));
        var circuitBreaker = circuitOpeningAfterTwoOutages();
        var bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        var subscription = verify(filter(circuitBreaker, bulkhead)).subscribe();
        awaitSessionVerificationRequest();

        subscription.dispose();

        assertEquals(1, bulkhead.getMetrics().getAvailableConcurrentCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
    }

    private static SessionAuthenticationFilter filter() {
        return filter(CircuitBreaker.ofDefaults("test"), Bulkhead.ofDefaults("test"));
    }

    private static SessionAuthenticationFilter filter(CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        var uri = URI.create(USER.uri() + PATH);
        return new SessionAuthenticationFilter(new SessionVerificationClient(WebClient.builder().build(), uri),
                new SessionVerificationProperties(uri, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)),
                circuitBreaker, bulkhead);
    }

    // 호출 2건 중 장애가 절반 이상이면 열리는 시험용 서킷(실제 값은 설정 파일)
    private static CircuitBreaker circuitOpeningAfterTwoOutages() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED).slidingWindowSize(10)
                .minimumNumberOfCalls(2).failureRateThreshold(50)
                .recordException(SessionVerificationConfiguration::isOutage).build());
    }

    // 대역 서버가 세션 확인 요청을 받을 때까지 기다림(최대 5초)
    private static void awaitSessionVerificationRequest() throws InterruptedException {
        for (var attempt = 0; attempt < 500 && USER.received(PATH).isEmpty(); attempt++) {
            Thread.sleep(10);
        }
        assertFalse(USER.received(PATH).isEmpty());
    }

    private static Mono<Void> verify(SessionAuthenticationFilter filter) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me")
                .header(HttpHeaders.COOKIE, "SESSION=abc").build());
        return filter.filter(exchange, ignored -> Mono.empty());
    }

    private static MockServerWebExchange routedTo(String destination, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("route").uri(destination).predicate(ignored -> true).build());
        return exchange;
    }
}
