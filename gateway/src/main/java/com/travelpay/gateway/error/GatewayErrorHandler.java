package com.travelpay.gateway.error;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private static final Set<Integer> UPSTREAM_FAILURES = Set.of(CommonErrorCode.BAD_GATEWAY.getStatus(),
            CommonErrorCode.SERVICE_UNAVAILABLE.getStatus(), CommonErrorCode.GATEWAY_TIMEOUT.getStatus());

    private static final String CLOSED_BEFORE_SEND = AbortedException.beforeSend().getMessage();

    // 다른 서비스 장애 응답(502·503·504)을 가장 안쪽 원인 예외별로 셈. 요청마다 로그를 남기지 않는 대신 원인은 지표로 봄
    static final String UPSTREAM_FAILURE_METRIC = "gateway.upstream.failures";

    private final JsonMapper mapper;
    private final MeterRegistry meterRegistry;

    public GatewayErrorHandler(JsonMapper mapper, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(exception);
        }
        // 다른 서비스의 응답 헤더를 받은 뒤 본문 전에 끊기면 그 헤더(쿠키·이동 주소 등)가 이미 복사돼 있으므로, Gateway가 만드는
        // 오류 응답에 섞이지 않게 이름별로 지움(SCG의 ServerWebExchangeUtils.reset과 같은 방식). 같은 이름이 없으면 Gateway 헤더는 남지만,
        // 양쪽이 모두 Vary 등을 붙였다면 Gateway의 값도 함께 지워질 수 있음. 헤더 출처를 따로 보관하지 않으므로 완전히 구분하지 못함
        Set<String> upstreamHeaders = exchange.getAttributeOrDefault(
                ServerWebExchangeUtils.CLIENT_RESPONSE_HEADER_NAMES, Set.of());
        upstreamHeaders.forEach(response.getHeaders()::remove);

        HttpStatusCode status = HttpStatusCode.valueOf(500);
        ErrorResponse error = ErrorResponse.fromHttpStatus(500);
        HttpHeaders headers = new HttpHeaders();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof BusinessException businessException) {
                var errorCode = businessException.getErrorCode();
                status = HttpStatusCode.valueOf(errorCode.getStatus());
                error = ErrorResponse.of(errorCode);
                break;
            }
            if (current instanceof org.springframework.web.ErrorResponse frameworkError) {
                status = frameworkError.getStatusCode();
                error = ErrorResponse.fromHttpStatus(status.value());
                headers.putAll(frameworkError.getHeaders());
                break;
            }
            var responseStatus = AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                status = responseStatus.code();
                error = ErrorResponse.fromHttpStatus(status.value());
                break;
            }
            // 이름 조회·연결 실패와 Netty가 전송 전이라고 표시한 중단만 503. AbortedException.isConnectionReset은 Broken pipe 등도 포함해 쓰지 않음.
            // BEFORE response는 이미 요청을 보냈을 수 있어 아래의 502로 구분합니다. Netty의 연결 시간 초과도 ConnectException입니다.
            if (current instanceof ConnectException || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException
                    || current instanceof AbortedException && CLOSED_BEFORE_SEND.equals(current.getMessage())) {
                status = HttpStatusCode.valueOf(CommonErrorCode.SERVICE_UNAVAILABLE.getStatus());
                error = ErrorResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE);
                break;
            }
            // 요청을 보낸 뒤 응답을 다 받기 전에 다른 서비스가 연결을 닫음(처리 중 종료 등). 이미 처리됐을 수 있어 재시도 가능한 503과 구분해 502
            if (current instanceof PrematureCloseException || isConnectionReset(current)) {
                status = HttpStatusCode.valueOf(CommonErrorCode.BAD_GATEWAY.getStatus());
                error = ErrorResponse.of(CommonErrorCode.BAD_GATEWAY);
                break;
            }
        }

        // 502·503·504는 다른 서비스 장애 때 요청마다 생기는 예상된 응답(건수는 HTTP 지표). 진짜 원인이 묻히지 않게 Gateway 자체 오류만 ERROR
        if (UPSTREAM_FAILURES.contains(status.value())) {
            meterRegistry.counter(UPSTREAM_FAILURE_METRIC, "status", String.valueOf(status.value()),
                    "cause", rootCause(exception).getClass().getSimpleName()).increment();
        }
        else if (status.is5xxServerError()) {
            log.error("Unexpected exception type: {}", exception.getClass().getName());
        }
        response.setStatusCode(status);
        response.getHeaders().putAll(headers);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 프레임워크 헤더를 복사한 뒤, HEAD를 포함한 오류 응답의 저장 금지를 명시합니다.
        response.getHeaders().setCacheControl(CacheControl.noStore());
        if (exchange.getRequest().getMethod() == HttpMethod.HEAD) {
            return response.setComplete();
        }

        byte[] payload = mapper.writeValueAsBytes(ApiResponse.failure(error));
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(payload)));
    }

    // 예: 세션 확인 API의 응답 대기 초과는 ReadTimeoutException, 다른 서비스 연결 실패는 ConnectException
    private static Throwable rootCause(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var root = exception;
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            root = current;
        }
        return root;
    }

    // 강제 종료(RST)된 연결은 운영체제·전송 방식마다 예외 종류와 문구가 달라('Connection reset by peer' 등) 공통 부분으로 구분
    private static boolean isConnectionReset(Throwable error) {
        return error instanceof IOException && error.getMessage() != null
                && error.getMessage().contains("Connection reset");
    }
}
