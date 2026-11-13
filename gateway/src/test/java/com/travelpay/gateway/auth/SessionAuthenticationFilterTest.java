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
                URI.create(USER.uri() + PATH), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)));
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
                uri, Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(150)));
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

    private static SessionAuthenticationFilter filter() {
        var uri = URI.create(USER.uri() + PATH);
        return new SessionAuthenticationFilter(new SessionVerificationClient(WebClient.builder().build(), uri),
                new SessionVerificationProperties(uri, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)));
    }

    private static MockServerWebExchange routedTo(String destination, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("route").uri(destination).predicate(ignored -> true).build());
        return exchange;
    }
}
