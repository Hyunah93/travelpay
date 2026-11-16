package com.travelpay.gateway.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 세션 확인 API 호출의 동시 호출 상한과 서킷 설정입니다. 값이 없거나 범위를 벗어나면 Gateway가 시작하지 않습니다. */
@ConfigurationProperties(SessionVerificationProtectionProperties.PREFIX)
public record SessionVerificationProtectionProperties(
        Integer maxConcurrentCalls, Duration failureWindow, Integer minimumCalls, Float failureRateThreshold,
        Duration openDuration, Integer halfOpenCalls) {

    static final String PREFIX = "travelpay.gateway.session-verification.protection";

    public SessionVerificationProtectionProperties {
        requireAtLeastOne("max-concurrent-calls", maxConcurrentCalls);
        // 서킷의 집계 구간은 초 단위로만 셈(Resilience4j 시간 기준 창)
        if (failureWindow == null || failureWindow.toMillis() < 1000 || failureWindow.toMillis() % 1000 != 0) {
            throw new IllegalArgumentException(PREFIX + ".failure-window는 1초 이상의 정수 초여야 합니다: " + failureWindow);
        }
        requireAtLeastOne("minimum-calls", minimumCalls);
        if (failureRateThreshold == null || !(failureRateThreshold > 0 && failureRateThreshold <= 100)) {
            throw new IllegalArgumentException(PREFIX + ".failure-rate-threshold는 0 초과 100 이하여야 합니다: "
                    + failureRateThreshold);
        }
        if (openDuration == null || openDuration.toMillis() < 1) {
            throw new IllegalArgumentException(PREFIX + ".open-duration은 1ms 이상이어야 합니다: " + openDuration);
        }
        requireAtLeastOne("half-open-calls", halfOpenCalls);
    }

    private static void requireAtLeastOne(String key, Integer value) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(PREFIX + "." + key + "는 1 이상이어야 합니다: " + value);
        }
    }
}
