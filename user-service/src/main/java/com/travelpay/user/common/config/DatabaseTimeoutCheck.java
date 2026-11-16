package com.travelpay.user.common.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 앱 풀의 읽기·취소 대기에 상한이 있고 비밀번호 변경·관리자 실행 기한이 그 읽기 상한보다 짧은지 시작할 때 확인합니다. */
@Component
class DatabaseTimeoutCheck {

    static final String SOCKET_TIMEOUT = "spring.datasource.hikari.data-source-properties.socketTimeout";
    private static final String CANCEL_TIMEOUT = "spring.datasource.hikari.data-source-properties.cancelSignalTimeout";
    private static final Set<String> URL_OVERRIDE_KEYS = Set.of("socketTimeout", "cancelSignalTimeout", "options");

    DatabaseTimeoutCheck(Environment environment, PasswordChangeProperties passwordChange, AdminProperties admin) {
        requireNoUrlOverrides(environment);
        var cancelTimeoutSeconds = environment.getProperty(CANCEL_TIMEOUT, Integer.class);
        if (cancelTimeoutSeconds != null && cancelTimeoutSeconds <= 0) {
            throw new IllegalStateException(CANCEL_TIMEOUT + "은 SQL 취소 연결·읽기에 유효한 상한을 두도록 양수여야 합니다.");
        }
        var socketTimeoutSeconds = environment.getProperty(SOCKET_TIMEOUT, Integer.class);
        if (socketTimeoutSeconds == null) {
            return;
        }
        if (socketTimeoutSeconds <= 0) {
            throw new IllegalStateException(SOCKET_TIMEOUT + "이 0 이하면 소켓 읽기 상한이 없으므로 양수여야 합니다.");
        }
        // 소켓 읽기 상한보다 먼저 JDBC가 SQL 취소를 시도할 여유를 둠. 전체 HTTP 요청의 하드 기한은 아님
        requireShorter("travelpay.user.password-change.transaction-timeout", passwordChange.transactionTimeout(),
                socketTimeoutSeconds);
        requireShorter("travelpay.user.admin.transaction-timeout", admin.transactionTimeout(), socketTimeoutSeconds);
    }

    private static void requireNoUrlOverrides(Environment environment) {
        var binder = Binder.get(environment);
        var url = binder.bind("spring.datasource.hikari.jdbc-url", String.class)
                .orElseGet(() -> binder.bind("spring.datasource.url", String.class).orElse(""));
        var queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return;
        }
        // PG JDBC Driver.parseURL의 query 규칙: 첫 ? 뒤를 &와 첫 =로 나누며 키는 URL decode하지 않음.
        // 전체 URL 파서는 쓰지 않음: 잘못된 URL의 로그 노출과 service/.pgpass 파일 조회 없이 금지 키만 검사함.
        for (var parameter : url.substring(queryStart + 1).split("&")) {
            var key = parameter.split("=", 2)[0];
            if (URL_OVERRIDE_KEYS.contains(key)) {
                throw new IllegalStateException("앱 풀 정책보다 우선하는 JDBC URL 속성은 지정할 수 없습니다: " + key);
            }
        }
    }

    private static void requireShorter(String key, Duration timeout, int socketTimeoutSeconds) {
        if (timeout.compareTo(Duration.ofSeconds(socketTimeoutSeconds)) >= 0) {
            throw new IllegalStateException(key + "은 앱 연결 풀의 소켓 읽기 상한(" + SOCKET_TIMEOUT + "="
                    + socketTimeoutSeconds + "초)보다 짧아야 합니다: " + timeout);
        }
    }
}
