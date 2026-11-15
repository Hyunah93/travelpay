package com.travelpay.gateway;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 실제 Gateway로, 세션 확인 호출이 동시 호출 상한(100)까지는 연결 풀에서 연결을 기다리지 않는지 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "logging.level.root=WARN"
})
class GatewaySessionVerificationConnectionLimitTest {

    private static final String SESSION_VERIFICATION = "/internal/v1/auth/session";
    private static final String WALLET_PATH = "/api/v1/wallets/me";
    private static final UUID MEMBER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    // reactor-netty 기본 풀 상한(CPU 수 × 2, 최소 16)보다 많이 보내야 전용 풀의 상한이 빠졌을 때 드러남
    private static final int CONCURRENT = ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS + 4;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MOST_IN_FLIGHT = new AtomicInteger();
    private static final Sinks.Empty<Void> ALL_ARRIVED = Sinks.empty();
    private static final DisposableServer SERVICES = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                response.header("Content-Type", "application/json");
                if (!request.fullPath().equals(SESSION_VERIFICATION)) {
                    return response.sendString(Mono.just(
                            "{\"success\":true,\"data\":{\"from\":\"service\"},\"error\":null}")).then();
                }
                var inFlight = IN_FLIGHT.incrementAndGet();
                MOST_IN_FLIGHT.accumulateAndGet(inFlight, Math::max);
                if (inFlight >= CONCURRENT) {
                    ALL_ARRIVED.tryEmitEmpty();
                }
                // 모두 동시에 도착할 때까지 응답을 붙잡음. 연결이 모자라 다 오지 못하면 1초 뒤 그대로 응답(응답 대기 3초 전)
                return ALL_ARRIVED.asMono().timeout(Duration.ofSeconds(1), Mono.empty())
                        .then(response.sendString(Mono.just("{\"success\":true,\"data\":{\"userId\":\"" + MEMBER_ID
                                + "\",\"role\":\"USER\"},\"error\":null}")).then())
                        .doFinally(signal -> IN_FLIGHT.decrementAndGet());
            }).bindNow();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void servicesOnTheHttpServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://127.0.0.1:" + SERVICES.port());
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri",
                () -> "http://127.0.0.1:" + SERVICES.port());
    }

    @AfterAll
    static void stop() {
        SERVICES.disposeNow();
    }

    // 전용 풀의 연결 상한이 기본값으로 돌아가면 그 수를 넘는 확인 요청은 연결이 빌 때까지 기다리며 응답 대기 예산을 씀
    @Test
    void sessionVerificationCallsUpToTheConcurrencyLimitDoNotWaitForAPooledConnection() {
        assumeTrue(CONCURRENT <= 100, "기본 풀 상한이 동시 호출 상한(100)보다 큰 장비에서는 차이를 만들 수 없음");
        var client = WebClient.create("http://127.0.0.1:" + port);

        var statuses = Flux.range(0, CONCURRENT)
                .flatMap(request -> client.get().uri(WALLET_PATH).header("Cookie", "SESSION=abc")
                        .exchangeToMono(response -> Mono.just(response.statusCode().value())), CONCURRENT)
                .collectList().block(Duration.ofSeconds(15));

        assertEquals(Collections.nCopies(CONCURRENT, 200), statuses);
        assertEquals(CONCURRENT, MOST_IN_FLIGHT.get());
    }
}
