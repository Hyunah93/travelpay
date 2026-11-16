package com.travelpay.gateway.auth;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVerificationClientTest {

    private static final String PATH = "/internal/v1/auth/session";
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final StubServer USER_SERVICE = StubServer.start();

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void reset() {
        USER_SERVICE.reset();
        log.start();
        ((Logger) LoggerFactory.getLogger(SessionVerificationClient.class)).addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(SessionVerificationClient.class)).detachAppender(log);
    }

    @AfterAll
    static void stop() {
        USER_SERVICE.close();
    }

    @Test
    void aVerifiedSessionBecomesTheMemberAndOnlyTheNeededHeadersAreSent() {
        USER_SERVICE.reply(PATH, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"MASTER"},"error":null}
                """.formatted(USER_ID)));
        var request = MockServerHttpRequest.post("/api/v1/transfers")
                .header("Cookie", "SESSION=abc").header("Csrf-Token", "csrf-1").header("Request-Id", "rid-1")
                .header("Authorization", "Bearer not-for-user-service").header("Content-Type", "application/json")
                .build();

        var result = client(Duration.ofSeconds(2)).verify(request).block();

        assertEquals(new SessionVerification.Verified(new AuthenticatedUser(USER_ID, AuthRole.MASTER)), result);
        var sent = USER_SERVICE.received(PATH).getFirst();
        assertEquals("POST", sent.method());
        assertEquals(List.of("SESSION=abc"), sent.header("Cookie"));
        assertEquals(List.of("csrf-1"), sent.header("Csrf-Token"));
        assertEquals(List.of("rid-1"), sent.header("Request-Id"));
        assertEquals(List.of("POST"), sent.header("Original-Method"));
        assertTrue(sent.header("Authorization").isEmpty());
    }

    @Test
    void fieldsUserServiceAddsLaterDoNotBreakVerification() {
        USER_SERVICE.reply(PATH, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"USER","status":"ACTIVE"},"error":null,"meta":{"version":1}}
                """.formatted(USER_ID)));

        var result = client(Duration.ofSeconds(2)).verify(walletRequest()).block();

        assertEquals(new SessionVerification.Verified(new AuthenticatedUser(USER_ID, AuthRole.USER)), result);
    }

    @Test
    void theOriginalMethodIsTheBrowserRequestsMethod() {
        USER_SERVICE.reply(PATH, verified());

        client(Duration.ofSeconds(2)).verify(MockServerHttpRequest.get("/api/v1/wallets/me").build()).block();

        assertEquals(List.of("GET"), USER_SERVICE.received(PATH).getFirst().header("Original-Method"));
        assertTrue(USER_SERVICE.received(PATH).getFirst().header("Cookie").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409})
    void aRejectionKeepsTheStatusAndBodyOfUserService(int status) {
        var body = """
                {"success":false,"data":null,"error":{"code":"USER_ACCOUNT_SUSPENDED","message":"m","details":[]}}
                """;
        USER_SERVICE.reply(PATH, Reply.json(status, body));

        var result = client(Duration.ofSeconds(2)).verify(walletRequest()).block();

        var rejected = assertInstanceOf(SessionVerification.Rejected.class, result);
        assertEquals(HttpStatus.valueOf(status), rejected.status());
        assertArrayEquals(body.getBytes(UTF_8), rejected.body());
    }

    // user-service 자체 장애는 장애 동안 요청마다 생기므로 ERROR로 남기지 않습니다.
    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void aUserServiceFailureIsAnAuthenticationOutageWithoutAnErrorLog(int status) {
        USER_SERVICE.reply(PATH, Reply.json(status, ""));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertTrue(errors().isEmpty(), errors().toString());
    }

    // 약속하지 않은 상태는 연동 오류라 상태와 함께 ERROR로 남깁니다.
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429})
    void anUnexpectedStatusIsAnAuthenticationOutageLoggedAsABrokenContract(int status) {
        USER_SERVICE.reply(PATH, Reply.json(status, ""));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertEquals(1, errors().size());
        assertTrue(errors().getFirst().getFormattedMessage().contains(String.valueOf(status)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "{\"success\":true,\"data\":null,\"error\":null}",
            "{\"success\":true,\"data\":{\"userId\":\"550e8400-e29b-41d4-a716-446655440000\"},\"error\":null}",
            "{\"success\":true,\"data\":{\"userId\":\"550e8400-e29b-41d4-a716-446655440000\",\"role\":\"ADMIN\"},\"error\":null}"
    })
    void aSuccessThatDoesNotCarryAMemberIsAnAuthenticationOutage(String body) {
        USER_SERVICE.reply(PATH, Reply.json(200, body));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertBrokenContractLogged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "\"0\"", "\"1\""})
    void aNumericRoleCannotAuthenticateAsAnEnumPosition(String role) {
        USER_SERVICE.reply(PATH, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":%s},"error":null}
                """.formatted(USER_ID, role)));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertBrokenContractLogged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "\"true\""})
    void successMustBeAJsonBooleanRatherThanAnAutomaticallyConvertedValue(String success) {
        USER_SERVICE.reply(PATH, Reply.json(200, """
                {"success":%s,"data":{"userId":"%s","role":"USER"},"error":null}
                """.formatted(success, USER_ID)));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertBrokenContractLogged();
    }

    @Test
    void aFailureEnvelopeWithA200StatusCannotAuthenticate() {
        USER_SERVICE.reply(PATH, Reply.json(200, """
                {"success":false,"data":null,"error":{"code":"COMMON_UNAUTHORIZED","message":"m","details":[]}}
                """));

        assertUnavailable(client(Duration.ofSeconds(2)));
        assertBrokenContractLogged();
    }

    @Test
    void theSuppliedClientsResponseSizeLimitIsPreserved() {
        USER_SERVICE.reply(PATH, verified());
        var limitedClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(2))))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64))
                .build();

        assertUnavailable(new SessionVerificationClient(limitedClient, URI.create(USER_SERVICE.uri() + PATH)));
    }

    @Test
    void aSlowUserServiceIsAnAuthenticationOutage() {
        USER_SERVICE.reply(PATH, verified().delayedBy(Duration.ofMillis(1500)));

        assertUnavailable(client(Duration.ofMillis(300)));
        assertTrue(errors().isEmpty(), errors().toString());
    }

    // 성공 상태를 받은 뒤 본문이 멈추거나 끊기는 것은 응답 형식 문제가 아니라 장애이므로 ERROR로 남기지 않습니다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSuccessBodyThatStallsOrIsCutOffIsAnOutageWithoutAnErrorLog(boolean stall) throws Exception {
        try (var server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().read(new byte[8192]);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: 200\r\n\r\n{\"success\":true,").getBytes(UTF_8));
                    socket.getOutputStream().flush();
                    if (stall) {
                        socket.getInputStream().read();
                    }
                } catch (IOException closed) {
                    // 클라이언트가 시간 초과로 연결을 닫으면 끝냅니다.
                }
            });
            var client = new SessionVerificationClient(webClient(Duration.ofMillis(300)),
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + PATH));

            assertUnavailable(client);
        }
        assertTrue(errors().isEmpty(), errors().toString());
    }

    @Test
    void anUnreachableUserServiceIsAnAuthenticationOutage() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var client = new SessionVerificationClient(webClient(Duration.ofSeconds(2)),
                URI.create("http://127.0.0.1:" + closedPort + PATH));

        var exception = assertThrows(BusinessException.class, () -> client.verify(walletRequest()).block());
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        assertTrue(errors().isEmpty(), errors().toString());
    }

    private void assertBrokenContractLogged() {
        assertEquals(1, errors().size());
        assertTrue(errors().getFirst().getFormattedMessage().startsWith("Session verification returned 200"),
                errors().getFirst().getFormattedMessage());
    }

    private List<ILoggingEvent> errors() {
        return log.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private static void assertUnavailable(SessionVerificationClient client) {
        var exception = assertThrows(BusinessException.class, () -> client.verify(walletRequest()).block());
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    private static Reply verified() {
        return Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"USER"},"error":null}
                """.formatted(USER_ID));
    }

    private static MockServerHttpRequest walletRequest() {
        return MockServerHttpRequest.get("/api/v1/wallets/me").header("Cookie", "SESSION=abc").build();
    }

    private static SessionVerificationClient client(Duration responseTimeout) {
        return new SessionVerificationClient(webClient(responseTimeout), URI.create(USER_SERVICE.uri() + PATH));
    }

    private static WebClient webClient(Duration responseTimeout) {
        var httpClient = HttpClient.create().responseTimeout(responseTimeout);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }
}
