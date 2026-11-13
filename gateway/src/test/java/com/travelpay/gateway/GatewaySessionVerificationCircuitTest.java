package com.travelpay.gateway;

import java.time.Duration;
import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 설정 파일의 값으로, 세션 확인 API 장애가 이어지면 서킷이 열려 업무 API가 세션 확인 없이 바로 503을 받고
 * 세션 확인을 거치지 않는 User API는 그대로 동작하는지 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "logging.level.root=WARN"
})
class GatewaySessionVerificationCircuitTest {

    private static final String SESSION_VERIFICATION = "/internal/v1/auth/session";
    private static final StubServer USER = StubServer.start();
    private static final StubServer WALLET = StubServer.start();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CircuitBreaker sessionVerificationCircuitBreaker;

    @Autowired
    private Bulkhead sessionVerificationBulkhead;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void servicesOnStubServers(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri", WALLET::uri);
    }

    @AfterAll
    static void stop() {
        List.of(USER, WALLET).forEach(StubServer::close);
    }

    @Test
    void repeatedOutagesOpenTheCircuitWhileUserApisKeepWorking() {
        USER.reply(SESSION_VERIFICATION, Reply.json(500, ""));

        for (var request = 0; request < 25; request++) {
            client().get().uri("/api/v1/wallets/me").header("Cookie", "SESSION=abc")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_SERVICE_UNAVAILABLE");
        }

        // 최소 호출 수(20)에 닿는 순간 열리고, 그 뒤 요청은 세션 확인을 부르지 않음
        assertEquals(20, USER.received(SESSION_VERIFICATION).size());
        assertTrue(WALLET.received().isEmpty());
        assertEquals(CircuitBreaker.State.OPEN, sessionVerificationCircuitBreaker.getState());
        assertEquals(1.0, meterRegistry.get("resilience4j.circuitbreaker.state")
                .tags("name", "session-verification", "state", "open").gauge().value());
        client().get().uri("/api/v1/users/me").header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();
        assertEquals(20, USER.received(SESSION_VERIFICATION).size());
    }

    // 세션 확인 클라이언트가 장애를 503 BusinessException으로 바꾸므로 그것만 실패로 셈. 그 밖의 예외는 User 장애가 아님
    @Test
    void onlySessionVerificationOutagesAreRecordedAsFailures() {
        var recorded = sessionVerificationCircuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate();

        assertTrue(recorded.test(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE)));
        assertFalse(recorded.test(new BusinessException(CommonErrorCode.UNAUTHORIZED)));
        assertFalse(recorded.test(new IllegalStateException("unexpected")));
    }

    // 허가를 기다리면 Gateway의 요청 처리 스레드가 멈추므로 실제 설정에서도 대기 없이 바로 거절해야 함
    @Test
    void theBulkheadNeverWaitsForAPermit() {
        assertEquals(Duration.ZERO, sessionVerificationBulkhead.getBulkheadConfig().getMaxWaitDuration());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
}
