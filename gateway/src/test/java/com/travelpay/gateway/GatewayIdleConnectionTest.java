package com.travelpay.gateway;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 실제 Gateway와 요청이 온 연결을 기록하는 대역으로, 오래 쉰 연결을 다시 쓰지 않는지 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        // 30초를 기다리지 않도록 줄임. 이어 보내는 요청 사이 간격보다는 충분히 김
        "spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time=1s",
        "logging.level.root=WARN"
})
class GatewayIdleConnectionTest {

    private static final String SESSION_VERIFICATION = "/internal/v1/auth/session";
    private static final String WALLET_PATH = "/api/v1/wallets/me";
    private static final UUID MEMBER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    // 경로별로 요청이 온 연결의 Gateway 쪽 주소. 주소가 같으면 같은 연결을 다시 쓴 것
    private static final Map<String, List<SocketAddress>> CONNECTIONS = new ConcurrentHashMap<>();
    private static final DisposableServer SERVICES = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                CONNECTIONS.computeIfAbsent(request.fullPath(), path -> new CopyOnWriteArrayList<>())
                        .add(request.remoteAddress());
                var body = request.fullPath().equals(SESSION_VERIFICATION)
                        ? "{\"success\":true,\"data\":{\"userId\":\"" + MEMBER_ID + "\",\"role\":\"USER\"},\"error\":null}"
                        : "{\"success\":true,\"data\":{\"from\":\"service\"},\"error\":null}";
                return response.header("Content-Type", "application/json").sendString(Mono.just(body)).then();
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

    // 다른 서비스가 쉰 연결을 닫는 순간 그 연결로 보낸 요청이 끊기지 않게, 세션 확인·라우팅 모두 상한보다 오래 쉰 연결은 새로 맺음
    @Test
    void connectionsIdleLongerThanTheLimitAreReplacedForBothSessionVerificationAndRouting() throws InterruptedException {
        requestWallet();
        Thread.sleep(100);
        requestWallet();
        Thread.sleep(1500);
        requestWallet();

        for (var path : List.of(SESSION_VERIFICATION, WALLET_PATH)) {
            var used = CONNECTIONS.get(path);
            assertEquals(3, used.size(), path);
            // 조금 쉬고 보낸 요청은 같은 연결을 다시 쓰므로, 마지막 요청의 새 연결은 쉰 시간 때문임
            assertEquals(used.get(0), used.get(1), path);
            assertNotEquals(used.get(1), used.get(2), path);
        }
    }

    private void requestWallet() {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(5)).build()
                .get().uri(WALLET_PATH).header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isOk();
    }
}
