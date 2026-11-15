package com.travelpay.gateway.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicEndpointsTest {

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/exchange-rates",
            "GET,/api/v1/exchange-rates/USD"
    })
    void aBusinessEndpointThatNeedsNoLoginIsPublic(String method, String path) {
        assertTrue(PublicEndpoints.contains(request(method, path)));
    }

    @ParameterizedTest
    // User API(회원가입·로그인·로그아웃 포함)는 User 라우트라 이 목록과 관계없이 user-service가 판단
    @CsvSource({
            "GET,/api/v1/auth/csrf",
            "POST,/api/v1/users",
            "POST,/api/v1/auth/logout",
            "GET,/api/v1/users/me",
            "PATCH,/api/v1/users",
            "POST,/api/v1/terms",
            "GET,/api/v1/auth/login",
            "GET,/api/v1/auth/csrf/extra",
            "GET,/api/v1/terms/",
            "POST,/api/v1/exchange-rates",
            "GET,/api/v1/exchange-limits",
            "GET,/api/v1/wallets/me",
            "POST,/api/v1/transfers",
            "GET,/api/v1/unknown"
    })
    void everythingElseIsProtected(String method, String path) {
        assertFalse(PublicEndpoints.contains(request(method, path)));
    }

    private static MockServerHttpRequest request(String method, String path) {
        return MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build();
    }
}
