package com.travelpay.gateway.auth;

import java.time.Duration;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 세션 확인 API 전용 WebClient·서킷·동시 호출 상한을 만듭니다. WebClient는 빈으로 내놓지 않아 다른 호출과 섞이지 않습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SessionVerificationProperties.class, SessionVerificationProtectionProperties.class})
class SessionVerificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionVerificationConfiguration.class);

    // 지표 이름(resilience4j_circuitbreaker_state{name="session-verification"} 등)에 쓰는 이름
    static final String PROTECTION_NAME = "session-verification";

    // Resilience4j의 Bulkhead 지표는 남은·최대 허가 수뿐이라 거절 수를 따로 셈(503 응답은 로그를 남기지 않음)
    static final String BULKHEAD_REJECTED_METRIC = "session.verification.bulkhead.rejected";

    @Bean
    SessionVerificationClient sessionVerificationClient(ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            ObservationRegistry observationRegistry, SessionVerificationProperties properties) {
        // 연결·응답 제한 시간. 넘으면 SessionVerificationClient가 503으로 바꿈
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.responseTimeout());
        var webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 주소의 user-service를 Eureka에서 찾은 실제 주소로 바꿈
                .filter(loadBalancer)
                // 이 호출도 요청의 traceId를 이어 받게 함(traceparent 헤더를 붙임)
                .observationRegistry(observationRegistry)
                .build();
        return new SessionVerificationClient(webClient, properties.uri());
    }

    // User가 응답하지 못하는 장애(503으로 바뀐 실패)만 세고, 401·403·409 거절은 결과 값이라 성공으로 셈.
    // 호출은 전체 상한(5초)을 넘지 못하므로 느린 호출 판정(기본 60초)은 생기지 않음
    @Bean
    CircuitBreaker sessionVerificationCircuitBreaker(SessionVerificationProtectionProperties properties,
            MeterRegistry meterRegistry) {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(Math.toIntExact(properties.failureWindow().toSeconds()))
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                // 요청이 없어도 열림 시간이 지나면 반열림으로 바꿔, 상태 지표가 조용한 시간에 열림으로 남지 않게 함
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(SessionVerificationConfiguration::isOutage)
                .build();
        var registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        var circuitBreaker = registry.circuitBreaker(PROTECTION_NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("세션 확인 API 서킷 상태 변경: {}", event.getStateTransition()));
        return circuitBreaker;
    }

    // 대기 시간이 있으면 허가를 기다리는 동안 Gateway의 요청 처리 스레드가 멈추므로 0으로 고정함(넘으면 바로 503)
    @Bean
    Bulkhead sessionVerificationBulkhead(SessionVerificationProtectionProperties properties,
            MeterRegistry meterRegistry) {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build();
        var registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
        var bulkhead = registry.bulkhead(PROTECTION_NAME);
        var rejected = Counter.builder(BULKHEAD_REJECTED_METRIC)
                .description("동시 호출 상한으로 바로 503을 준 세션 확인 요청 수")
                .register(meterRegistry);
        bulkhead.getEventPublisher().onCallRejected(event -> rejected.increment());
        return bulkhead;
    }

    static boolean isOutage(Throwable error) {
        return error instanceof BusinessException failure
                && failure.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE;
    }
}
