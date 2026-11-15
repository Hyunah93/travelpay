package com.travelpay.gateway.auth;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 로그인 없이 쓰는 업무 API 목록. 여기 없는 업무 API 요청은 세션 확인 API를 거칩니다(User API의 공개 여부는 user-service가 정함). */
final class PublicEndpoints {

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/v1/exchange-rates/**"));

    private PublicEndpoints() {
    }

    static boolean contains(ServerHttpRequest request) {
        return ENDPOINTS.stream().anyMatch(endpoint -> endpoint.matches(request));
    }

    private record Endpoint(HttpMethod method, PathPattern path) {

        Endpoint(HttpMethod method, String path) {
            this(method, PathPatternParser.defaultInstance.parse(path));
        }

        boolean matches(ServerHttpRequest request) {
            return method.equals(request.getMethod()) && path.matches(request.getPath().pathWithinApplication());
        }
    }
}
