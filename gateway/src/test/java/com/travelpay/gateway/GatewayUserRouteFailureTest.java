package com.travelpay.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travelpay.gateway.error.GatewayErrorHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 설정 파일의 라우트로 Gateway를 띄우고, user-service 자리에 응답을 멈추거나 도중에 연결을 끊는 서버를 둬서
 * 세션 확인 API를 거치지 않는 User 라우트의 실패 응답을 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "logging.level.root=WARN"
})
class GatewayUserRouteFailureTest {

    private static final RawUserService USER = RawUserService.start();

    private final ListAppender<ILoggingEvent> errorLog = new ListAppender<>();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void userServiceOnARawServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
    }

    @BeforeEach
    void captureErrorHandlerLog() {
        USER.reset();
        errorLog.start();
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).addAppender(errorLog);
    }

    @AfterEach
    void releaseErrorHandlerLog() {
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).detachAppender(errorLog);
    }

    @AfterAll
    static void stop() {
        USER.close();
    }

    // 설정 파일의 실제 상한(10초)으로 확인하므로 이 시험은 약 10초 걸립니다.
    @Test
    void aUserResponseThatNeverStartsIs504WithTheSharedJsonAfterTheConfiguredLimit() {
        USER.behave(Behavior.NEVER_RESPOND);
        var started = System.nanoTime();

        client().get().uri("/api/v1/users/me").header("Cookie", "SESSION=abc")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_GATEWAY_TIMEOUT");

        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) >= 0 && elapsed.compareTo(Duration.ofSeconds(13)) < 0,
                elapsed.toString());
        assertEquals(List.of("/api/v1/users/me"), USER.received());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    @Test
    void aUserConnectionClosedBeforeTheResponseIs502AndTheRequestIsNotSentAgain() {
        USER.behave(Behavior.CLOSE_BEFORE_RESPONSE);

        client().patch().uri("/api/v1/users/me/password").header("Cookie", "SESSION=abc")
                .header("Csrf-Token", "csrf-1").header("Content-Type", "application/json").bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_BAD_GATEWAY");

        assertEquals(List.of("/api/v1/users/me/password"), USER.received());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    // 처리 중 강제 종료처럼 상대가 RST로 끊으면 정상 종료(FIN)와 다른 예외가 오지만, 같은 실패라 502입니다.
    @Test
    void aUserConnectionResetBeforeTheResponseIs502AndTheRequestIsNotSentAgain() {
        USER.behave(Behavior.RESET_BEFORE_RESPONSE);

        client().patch().uri("/api/v1/users/me/password").header("Cookie", "SESSION=abc")
                .header("Csrf-Token", "csrf-1").header("Content-Type", "application/json").bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_BAD_GATEWAY");

        assertEquals(List.of("/api/v1/users/me/password"), USER.received());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    @Test
    void aUserResponseCutOffAfterItStartedIsNotReplacedWithAnErrorJson() throws IOException {
        USER.behave(Behavior.CUT_OFF_BODY);

        var response = raw("GET", "/api/v1/users/me");

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        var body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertTrue(body.startsWith(RawUserService.PARTIAL_BODY), response);
        assertTrue(body.length() < RawUserService.DECLARED_LENGTH, response);
        assertFalse(response.contains("COMMON_"), response);
    }

    private List<ILoggingEvent> errors() {
        return errorLog.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private String raw(String method, String path) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Cookie: SESSION=abc\r\nConnection: close\r\n\r\n").getBytes(ISO_8859_1));
            var response = new ByteArrayOutputStream();
            try {
                socket.getInputStream().transferTo(response);
            } catch (SocketException reset) {
                // 응답 도중 끊긴 연결은 정상 종료 대신 재설정으로 끝날 수 있습니다. 그때까지 받은 내용을 봅니다.
            }
            return response.toString(UTF_8);
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    enum Behavior { NEVER_RESPOND, CLOSE_BEFORE_RESPONSE, RESET_BEFORE_RESPONSE, CUT_OFF_BODY }

    /** 요청을 읽은 뒤 정해 둔 방식으로 응답을 멈추거나 끊는 user-service 대역입니다. 받은 요청의 경로를 기록합니다. */
    static final class RawUserService implements AutoCloseable {

        static final String PARTIAL_BODY = "{\"success\":true,";
        static final int DECLARED_LENGTH = 100;

        private final ServerSocket server;
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final List<Socket> held = new CopyOnWriteArrayList<>();
        private volatile Behavior behavior = Behavior.NEVER_RESPOND;

        private RawUserService(ServerSocket server) {
            this.server = server;
        }

        static RawUserService start() {
            try {
                var server = new ServerSocket();
                server.bind(new InetSocketAddress("127.0.0.1", 0));
                var stub = new RawUserService(server);
                Thread.ofVirtual().start(stub::accept);
                return stub;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String uri() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        void behave(Behavior next) {
            behavior = next;
        }

        List<String> received() {
            return List.copyOf(received);
        }

        void reset() {
            received.clear();
            behavior = Behavior.NEVER_RESPOND;
        }

        @Override
        public void close() {
            try {
                server.close();
                for (var socket : held) {
                    socket.close();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    var socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        // 요청 하나를 읽은 뒤 정해 둔 방식으로 멈추거나 끊습니다. 어느 방식이든 이 연결은 다시 쓰이지 않습니다.
        private void serve(Socket socket) {
            try (socket) {
                var input = socket.getInputStream();
                var head = readHead(input);
                if (head == null) {
                    return;
                }
                input.readNBytes(contentLength(head));
                received.add(head.substring(head.indexOf(' ') + 1, head.indexOf(' ', head.indexOf(' ') + 1)));
                switch (behavior) {
                    case NEVER_RESPOND -> {
                        // Gateway가 기다리다 연결을 닫을 때까지 응답하지 않습니다.
                        held.add(socket);
                        input.read();
                    }
                    case CLOSE_BEFORE_RESPONSE -> {
                    }
                    // 닫을 때 남은 데이터를 버리고 RST를 보내게 합니다.
                    case RESET_BEFORE_RESPONSE -> socket.setSoLinger(true, 0);
                    case CUT_OFF_BODY -> {
                        var output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + DECLARED_LENGTH + "\r\n\r\n" + PARTIAL_BODY).getBytes(UTF_8));
                        output.flush();
                    }
                }
            } catch (IOException closed) {
                // Gateway가 연결을 닫으면 끝냅니다.
            }
        }

        private static String readHead(InputStream input) throws IOException {
            var head = new ByteArrayOutputStream();
            int last4 = 0;
            int next;
            while ((next = input.read()) != -1) {
                head.write(next);
                last4 = (last4 << 8) | next;
                if (last4 == 0x0D0A0D0A) {
                    return head.toString(ISO_8859_1);
                }
            }
            return null;
        }

        private static int contentLength(String head) {
            return head.lines()
                    .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                    .map(line -> Integer.parseInt(line.substring(15).strip()))
                    .findFirst().orElse(0);
        }
    }
}
