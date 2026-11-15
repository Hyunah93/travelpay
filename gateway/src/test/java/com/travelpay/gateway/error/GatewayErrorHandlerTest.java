package com.travelpay.gateway.error;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayErrorHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GatewayErrorHandler handler = new GatewayErrorHandler(mapper, meterRegistry);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        log.start();
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).detachAppender(log);
    }

    @Test
    void aServiceClosingTheConnectionBeforeItsResponseIsA502() {
        var exchange = exchange();

        handler.handle(exchange, PrematureCloseException.TEST_EXCEPTION).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_BAD_GATEWAY", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void aWrappedEarlyCloseIsA502WithoutTheWrapperMessage() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER", PrematureCloseException.TEST_EXCEPTION);

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_BAD_GATEWAY", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    // 강제 종료(RST)는 전송 방식에 따라 Netty 기본 전송·JDK 소켓 예외로 옵니다.
    @ParameterizedTest
    @ValueSource(strings = {"recvAddress(..) failed: Connection reset by peer", "Connection reset"})
    void aServiceResettingTheConnectionIsA502(String message) {
        var exchange = exchange();

        handler.handle(exchange, new SocketException(message)).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_BAD_GATEWAY", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void anotherIoFailureStaysAnUnexpected500() {
        var exchange = exchange();

        handler.handle(exchange, new IOException("Broken pipe")).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals(1, errors().size());
    }

    @ParameterizedTest
    @MethodSource("failuresBeforeSending")
    void aFailureBeforeSendingIsA503WithoutAnErrorLog(Throwable failure, String cause) {
        var exchange = exchange();

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
        assertTrue(errors().isEmpty(), errors().toString());
        assertEquals(1.0, upstreamFailures("503", cause));
    }

    static List<Arguments> failuresBeforeSending() {
        return List.of(
                Arguments.of(new UnknownHostException("DO_NOT_EXPOSE_HOST"), "UnknownHostException"),
                Arguments.of(new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                        new UnknownHostException("DO_NOT_EXPOSE_HOST")), "UnknownHostException"),
                Arguments.of(AbortedException.beforeSend(), "AbortedException"),
                Arguments.of(new IllegalStateException("DO_NOT_EXPOSE_WRAPPER", AbortedException.beforeSend()),
                        "AbortedException"));
    }

    @Test
    void anAbortWithoutTheBeforeSendMarkerStaysAnUnexpected500() {
        var exchange = exchange();

        handler.handle(exchange, new AbortedException("DO_NOT_EXPOSE_UNCLASSIFIED_ABORT"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals(1, errors().size());
    }

    // 다른 서비스의 장애로 생기는 응답은 요청마다 반복되므로 ERROR로 남기지 않습니다.
    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void expectedUpstreamFailuresAreNotLoggedAsErrors(Throwable failure, int status) {
        var exchange = exchange();

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(status, exchange.getResponse().getStatusCode().value());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    static List<Arguments> upstreamFailures() {
        return List.of(
                Arguments.of(PrematureCloseException.TEST_EXCEPTION, 502),
                Arguments.of(new SocketException("Connection reset"), 502),
                Arguments.of(new ConnectException("Connection refused"), 503),
                Arguments.of(
                        new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE), 503),
                Arguments.of(
                        new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout"), 504));
    }

    // 로그 대신 지표로 원인을 봄: 가장 안쪽 원인 예외별로 셈
    @Test
    void expectedUpstreamFailuresAreCountedByTheirRootCause() {
        handler.handle(exchange(), new ConnectException("Connection refused")).block(Duration.ofSeconds(2));
        handler.handle(exchange(), new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE,
                new IllegalStateException("wrapper", new TimeoutException()))).block(Duration.ofSeconds(2));
        handler.handle(exchange(), PrematureCloseException.TEST_EXCEPTION).block(Duration.ofSeconds(2));
        handler.handle(exchange(), new IllegalStateException("not an upstream failure")).block(Duration.ofSeconds(2));

        assertEquals(1.0, upstreamFailures("503", "ConnectException"));
        assertEquals(1.0, upstreamFailures("503", "TimeoutException"));
        assertEquals(1.0, upstreamFailures("502", "PrematureCloseException"));
        assertEquals(3, meterRegistry.find(GatewayErrorHandler.UPSTREAM_FAILURE_METRIC).counters().size());
    }

    @Test
    void anUnclassifiedFailureIsLoggedAsAnErrorWithItsTypeOnly() {
        handler.handle(exchange(), new IllegalStateException("DO_NOT_LOG_DETAIL")).block(Duration.ofSeconds(2));

        assertEquals(1, errors().size());
        assertTrue(errors().getFirst().getFormattedMessage().contains(IllegalStateException.class.getName()));
        assertFalse(errors().getFirst().getFormattedMessage().contains("DO_NOT_LOG_DETAIL"));
    }

    // 프레임워크가 분류한 500은 Gateway 자체 오류라 계속 ERROR로 남깁니다.
    @Test
    void aClassifiedInternalErrorIsStillLogged() {
        handler.handle(exchange(), new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR))
                .block(Duration.ofSeconds(2));

        assertEquals(1, errors().size());
    }

    private double upstreamFailures(String status, String cause) {
        return meterRegistry.get(GatewayErrorHandler.UPSTREAM_FAILURE_METRIC).tags("status", status, "cause", cause)
                .counter().count();
    }

    private List<ILoggingEvent> errors() {
        return log.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    @Test
    void businessErrorUsesTheSharedJsonContract() {
        var exchange = exchange();

        handler.handle(exchange, new BusinessException(CommonErrorCode.UNAUTHORIZED)).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("COMMON_UNAUTHORIZED", errorBody(exchange).get("code").stringValue());
        assertEquals(0, errorBody(exchange).get("details").size());
    }

    @Test
    void frameworkFailureKeeps503WithoutItsInternalReason() {
        var exchange = exchange();

        handler.handle(exchange, new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "DO_NOT_EXPOSE_INTERNAL_REASON")).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void frameworkHeadersArePreservedButTheirCachePolicyIsReplaced() {
        var exchange = exchange();
        var failure = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
        failure.getHeaders().setCacheControl("public, max-age=600");
        failure.getHeaders().set(HttpHeaders.RETRY_AFTER, "120");

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("120", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void methodErrorPreservesTheAllowHeader() {
        var exchange = exchange();

        handler.handle(exchange, new MethodNotAllowedException("GET", Set.of(HttpMethod.POST)))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
        assertEquals(Set.of(HttpMethod.POST), exchange.getResponse().getHeaders().getAllow());
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void unexpectedFailureReturnsASafe500() {
        var exchange = exchange();

        handler.handle(exchange, new IllegalStateException("DO_NOT_EXPOSE_INTERNAL_DETAIL"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_INTERNAL_SERVER_ERROR", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void connectTimeoutToAServiceIsA503WithoutTheAddress() {
        var exchange = exchange();

        handler.handle(exchange, new ConnectTimeoutException("connection timed out after 2000 ms: /10.20.10.5:8080"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("10.20.10.5"));
    }

    @Test
    void refusedConnectionToAServiceIsA503() {
        var exchange = exchange();

        handler.handle(exchange, new ConnectException("Connection refused: /10.20.10.5:8080"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void unreachableServiceHostIsA503() {
        var exchange = exchange();

        handler.handle(exchange, new NoRouteToHostException("No route to host: /10.20.10.5:8080"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void wrappedConnectionFailureIsA503() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new ConnectTimeoutException("connection timed out after 2000 ms: /10.20.10.5:8080"));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void explicitOuterStatusTakesPrecedenceOverAConnectionFailureCause() {
        var exchange = exchange();
        var failure = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "DO_NOT_EXPOSE_REASON",
                new ConnectTimeoutException("connection timed out after 2000 ms: /10.20.10.5:8080"));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_GATEWAY_TIMEOUT", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void unknownHttpStatusIsPreservedWithASafeFallbackBody() {
        var exchange = exchange();

        handler.handle(exchange, new ResponseStatusException(HttpStatusCode.valueOf(418), "DO_NOT_EXPOSE_REASON"))
                .block(Duration.ofSeconds(2));

        assertEquals(418, exchange.getResponse().getStatusCode().value());
        assertEquals("COMMON_HTTP_ERROR", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void committedResponseIsNotRewritten() {
        var exchange = exchange();
        exchange.getResponse().getHeaders().setCacheControl("private, max-age=60");
        exchange.getResponse().setComplete().block(Duration.ofSeconds(2));
        var failure = new IllegalStateException("already committed");

        var propagated = assertThrows(IllegalStateException.class,
                () -> handler.handle(exchange, failure).block(Duration.ofSeconds(2)));

        assertSame(failure, propagated);
        assertEquals("private, max-age=60", exchange.getResponse().getHeaders().getCacheControl());
    }

    @Test
    void annotatedStatusKeeps404WithoutTheAnnotationReason() {
        var exchange = exchange();

        handler.handle(exchange, new AnnotatedNotFoundException()).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedFrameworkFailureKeeps503WithoutExposingEitherReason() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DO_NOT_EXPOSE_REASON"));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedMethodErrorPreservesTheAllowHeader() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new MethodNotAllowedException("PATCH", Set.of(HttpMethod.POST)));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
        assertEquals(Set.of(HttpMethod.POST), exchange.getResponse().getHeaders().getAllow());
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void wrappedBusinessErrorKeepsItsCodeAndStatus() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new BusinessException(CommonErrorCode.UNAUTHORIZED));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_UNAUTHORIZED", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedAnnotatedStatusKeeps404WithoutTheAnnotationReason() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER", new AnnotatedNotFoundException());

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void explicitOuterStatusTakesPrecedenceOverItsCause() {
        var exchange = exchange();
        var failure = new AnnotatedNotFoundException();
        failure.initCause(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void headFailureKeepsItsStatusWithoutWritingAJsonBody() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.head("/test").build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals("", exchange.getResponse().getBodyAsString().defaultIfEmpty("").block(Duration.ofSeconds(2)));
    }

    @Test
    void cyclicCausesTerminateWithASafe500() {
        var exchange = exchange();
        var first = new IllegalStateException("DO_NOT_EXPOSE_CYCLE");
        var second = new RuntimeException(first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            handler.handle(exchange, first).block(Duration.ofSeconds(2));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        });
    }

    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = "DO_NOT_EXPOSE_ANNOTATED_REASON")
    static class AnnotatedNotFoundException extends RuntimeException {
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    private JsonNode errorBody(MockServerWebExchange exchange) {
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(2)));
        assertEquals(3, body.size());
        assertNotNull(body.get("success"));
        assertFalse(body.get("success").booleanValue());
        assertNotNull(body.get("data"));
        assertTrue(body.get("data").isNull());
        assertNotNull(body.get("error"));
        assertTrue(body.get("error").isObject());
        assertNotNull(body.get("error").get("details"));
        assertTrue(body.get("error").get("details").isArray());
        return body.get("error");
    }
}
