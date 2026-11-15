package com.travelpay.gateway.auth;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVerificationConfigurationTest {

    private final SessionVerificationConfiguration configuration = new SessionVerificationConfiguration();

    @Test
    void circuitSettingsComeFromTheProtectionProperties() {
        var properties = new SessionVerificationProtectionProperties(7, Duration.ofSeconds(9), 23, 67f,
                Duration.ofSeconds(13), 3);

        var circuitBreaker = configuration.sessionVerificationCircuitBreaker(properties, new SimpleMeterRegistry());
        var config = circuitBreaker.getCircuitBreakerConfig();

        assertEquals(SlidingWindowType.TIME_BASED, config.getSlidingWindowType());
        assertEquals(9, config.getSlidingWindowSize());
        assertEquals(23, config.getMinimumNumberOfCalls());
        assertEquals(67f, config.getFailureRateThreshold());
        assertEquals(13_000L, config.getWaitIntervalFunctionInOpenState().apply(1));
        assertEquals(3, config.getPermittedNumberOfCallsInHalfOpenState());
        assertTrue(config.isAutomaticTransitionFromOpenToHalfOpenEnabled());
    }

    // 거절된 503은 로그를 남기지 않으므로 지표로만 알 수 있음
    @Test
    void bulkheadRejectionsAreCounted() {
        var meterRegistry = new SimpleMeterRegistry();
        var bulkhead = configuration.sessionVerificationBulkhead(properties(1, Duration.ofSeconds(5)), meterRegistry);

        assertTrue(bulkhead.tryAcquirePermission());
        assertFalse(bulkhead.tryAcquirePermission());

        assertEquals(1.0, meterRegistry.get(SessionVerificationConfiguration.BULKHEAD_REJECTED_METRIC).counter().count());
    }

    // 요청이 없어도 열림 시간이 지나면 반열림으로 바뀌어, 상태 지표가 열림으로 남지 않음
    @Test
    void anOpenCircuitMovesToHalfOpenWithoutTraffic() throws InterruptedException {
        var circuitBreaker = configuration.sessionVerificationCircuitBreaker(
                properties(10, Duration.ofMillis(200)), new SimpleMeterRegistry());
        circuitBreaker.transitionToOpenState();

        for (var attempt = 0; attempt < 100 && circuitBreaker.getState() == CircuitBreaker.State.OPEN; attempt++) {
            Thread.sleep(20);
        }

        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    private static SessionVerificationProtectionProperties properties(int maxConcurrentCalls, Duration openDuration) {
        return new SessionVerificationProtectionProperties(maxConcurrentCalls, Duration.ofSeconds(10), 20, 50f,
                openDuration, 5);
    }
}
