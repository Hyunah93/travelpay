package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 변경의 DB 트랜잭션에서 JDBC에 전달할 실행 기한입니다(기본 5초, 해시 연산 제외).
 * 앱 풀의 개별 잠금 획득 대기는 PostgreSQL lock_timeout(2초)도 동시에 적용받습니다.
 * 이 값은 연결 획득·해시 연산을 포함한 전체 HTTP 요청을 정해진 시각에 강제 종료하는 보장이 아닙니다.
 */
@ConfigurationProperties("travelpay.user.password-change")
public record PasswordChangeProperties(Duration transactionTimeout) {

    public PasswordChangeProperties {
        if (transactionTimeout == null || transactionTimeout.isNegative() || transactionTimeout.isZero()
                || transactionTimeout.getNano() != 0 || transactionTimeout.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "travelpay.user.password-change.transaction-timeout은 단위를 붙인 1초 이상의 정수 초여야 합니다(예: 5s).");
        }
    }
}
