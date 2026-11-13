package com.travelpay.gateway.auth;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.request.InternalHeaderResetFilter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 업무 API 요청은 세션 확인 API로 확인해 회원 헤더를 붙이거나 거절하고, User 라우트는 user-service가 직접 인증하므로 그대로 넘깁니다. */
@Component
class SessionAuthenticationFilter implements GlobalFilter, Ordered {

    // 헤더 리셋 다음, 쿠키를 지우는 라우트 필터(순서 1부터)보다 먼저. 그래야 세션 확인에 원래 쿠키를 넘길 수 있음
    static final int ORDER = InternalHeaderResetFilter.ORDER + 100;

    // user-service 라우트의 목적지. 표기가 다르면 세션 확인 API를 거치는 쪽(기존 동작)으로 남음
    private static final URI USER_SERVICE = URI.create("lb://user-service");

    private final SessionVerificationClient sessionVerificationClient;
    private final Duration verificationTimeout;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    SessionAuthenticationFilter(SessionVerificationClient sessionVerificationClient,
            SessionVerificationProperties properties, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.sessionVerificationClient = sessionVerificationClient;
        // 전체 호출 상한은 연결·읽기 제한과 별도로 적용합니다.
        this.verificationTimeout = properties.totalTimeout();
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (PublicEndpoints.contains(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        if (!AuthHeaders.ALLOWED_ORIGINAL_METHODS.contains(exchange.getRequest().getMethod().name())) {
            // 세션 확인 API가 받지 않는 메소드는 호출 전에 405와 허용 메소드 목록으로 거절합니다.
            return Mono.error(new MethodNotAllowedException(exchange.getRequest().getMethod(),
                    AuthHeaders.ALLOWED_ORIGINAL_METHODS.stream().map(HttpMethod::valueOf).toList()));
        }
        if (routesToUserService(exchange)) {
            // user-service가 이 요청에서 세션·회원 상태·권한·CSRF를 직접 확인하므로 세션 확인 API를 부르지 않고 회원 헤더도 붙이지 않음
            return chain.filter(exchange);
        }
        return sessionVerificationClient.verify(exchange.getRequest())
                // 연결 전 대기부터 본문 해석 완료까지 제한하며, 이후 업무 서비스 호출은 이 범위 밖입니다.
                .timeout(verificationTimeout)
                .onErrorMap(TimeoutException.class,
                        error -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, error))
                // 서킷은 시간 초과까지 실패로 세도록 전체 상한 바깥에, 동시 호출 상한은 그 거절이 서킷 통계에 들지 않게 가장 바깥에 둠
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                // 서킷이 열렸거나 동시 호출이 가득 차면 세션 확인 없이 바로 503. 이전 결과로 통과시키지 않고 쿠키도 그대로 둠
                .onErrorMap(error -> error instanceof CallNotPermittedException || error instanceof BulkheadFullException,
                        error -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, error))
                .flatMap(result -> switch (result) {
                    case SessionVerification.Verified verified -> chain.filter(withMember(exchange, verified.user()));
                    case SessionVerification.Rejected rejected -> reject(exchange, rejected);
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // Gateway가 이미 고른 라우트의 목적지로 판단. 경로 목록이 없어 새 업무 라우트는 기본으로 세션 확인 API를 거침
    private static boolean routesToUserService(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null && USER_SERVICE.equals(route.getUri());
    }

    // 세션 확인 API가 알려 준 회원으로 User-Id·User-Role을 붙임. 브라우저가 보낸 값은 앞 필터가 이미 지움
    private static ServerWebExchange withMember(ServerWebExchange exchange, AuthenticatedUser user) {
        var request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(AuthHeaders.USER_ID, user.userId().toString());
                    headers.set(AuthHeaders.USER_ROLE, user.role().name());
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    // user-service의 거절(401·403·409)을 상태와 본문 그대로 돌려줌. HEAD 요청이면 Netty가 본문을 빼고 보냄
    private static Mono<Void> reject(ServerWebExchange exchange, SessionVerification.Rejected rejected) {
        var response = exchange.getResponse();
        response.setStatusCode(rejected.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 인증 거절 응답의 저장 금지를 Gateway에서도 명시합니다.
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return response.writeWith(Mono.just(response.bufferFactory().wrap(rejected.body())));
    }
}
