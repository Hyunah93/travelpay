package com.travelpay.gateway.auth;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVerificationProtectionPropertiesTest {

    @Test
    void validValuesAreKept() {
        var properties = new SessionVerificationProtectionProperties(100, Duration.ofSeconds(10), 20, 50f,
                Duration.ofSeconds(5), 5);

        assertEquals(100, properties.maxConcurrentCalls());
        assertEquals(Duration.ofSeconds(10), properties.failureWindow());
    }

    // 값이 없거나 범위를 벗어나면 서킷·동시 호출 상한이 의도와 다르게 동작하므로 시작하지 않음
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null,10000,20,50,5000,5,max-concurrent-calls",
            "0,10000,20,50,5000,5,max-concurrent-calls",
            "100,null,20,50,5000,5,failure-window",
            "100,500,20,50,5000,5,failure-window",
            "100,1500,20,50,5000,5,failure-window",
            "100,10000,0,50,5000,5,minimum-calls",
            "100,10000,20,0,5000,5,failure-rate-threshold",
            "100,10000,20,100.5,5000,5,failure-rate-threshold",
            "100,10000,20,50,0,5,open-duration",
            "100,10000,20,50,5000,0,half-open-calls"
    })
    void invalidValuesStopTheGateway(Integer maxCalls, Long windowMillis, Integer minimumCalls, Float threshold,
            Long openMillis, Integer halfOpenCalls, String key) {
        var error = assertThrows(IllegalArgumentException.class, () -> new SessionVerificationProtectionProperties(
                maxCalls, windowMillis == null ? null : Duration.ofMillis(windowMillis), minimumCalls, threshold,
                openMillis == null ? null : Duration.ofMillis(openMillis), halfOpenCalls));

        assertTrue(error.getMessage().contains(key), error.getMessage());
    }
}
