package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.correction.dto.ChargeReversalResult;
import feign.FeignException;
import feign.codec.EncodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.stream.Stream;

import static com.travelpay.wallet.infra.client.FeignFailures.connectionRefused;
import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.response;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerChargeReversalClientAdapterTest {

    private static final long AMOUNT = 10_000;

    private final LedgerTransactionFeignClient feignClient = mock(LedgerTransactionFeignClient.class);
    private final LedgerChargeReversalClientAdapter adapter = new LedgerChargeReversalClientAdapter(feignClient);

    @Test
    void POSTED이고_회수_미수_합이_충전_금액이면_Reversed() {
        UUID transactionId = UUID.randomUUID();
        givenBody(new ChargeReversalResponse(transactionId, "POSTED", 4_000L, 6_000L));

        assertEquals(new ChargeReversalResult.Reversed(transactionId, 4_000, 6_000), reverse());
    }

    @ParameterizedTest
    @CsvSource({"10000, 0", "0, 10000"})
    void 회수나_미수가_0이어도_합이_맞으면_Reversed(long recovered, long receivable) {
        UUID transactionId = UUID.randomUUID();
        givenBody(new ChargeReversalResponse(transactionId, "POSTED", recovered, receivable));

        assertEquals(new ChargeReversalResult.Reversed(transactionId, recovered, receivable), reverse());
    }

    @Test
    void 요청은_chargeId와_description만_보낸다() {
        UUID chargeId = UUID.randomUUID();
        givenBody(new ChargeReversalResponse(UUID.randomUUID(), "POSTED", AMOUNT, 0L));

        adapter.reverse(chargeId, AMOUNT, "이중 지급 바로잡기");

        ArgumentCaptor<ChargeReversalRequest> captor = ArgumentCaptor.forClass(ChargeReversalRequest.class);
        verify(feignClient).reverseCharge(captor.capture());
        assertEquals(new ChargeReversalRequest(chargeId, "이중 지급 바로잡기"), captor.getValue());
    }

    static Stream<Arguments> invalidBodies() {
        UUID id = UUID.randomUUID();
        return Stream.of(
                Arguments.of("transactionId 없음", new ChargeReversalResponse(null, "POSTED", 4_000L, 6_000L)),
                Arguments.of("status가 POSTED 아님", new ChargeReversalResponse(id, "REJECTED", 4_000L, 6_000L)),
                Arguments.of("status 없음", new ChargeReversalResponse(id, null, 4_000L, 6_000L)),
                Arguments.of("금액 합 불일치(작음)", new ChargeReversalResponse(id, "POSTED", 4_000L, 5_999L)),
                Arguments.of("금액 합 불일치(큼)", new ChargeReversalResponse(id, "POSTED", 4_000L, 6_001L)),
                Arguments.of("회수 금액 음수", new ChargeReversalResponse(id, "POSTED", -1L, 10_001L)),
                Arguments.of("미수 금액 음수", new ChargeReversalResponse(id, "POSTED", 10_001L, -1L)),
                Arguments.of("회수 금액 없음", new ChargeReversalResponse(id, "POSTED", null, AMOUNT)),
                Arguments.of("미수 금액 없음", new ChargeReversalResponse(id, "POSTED", AMOUNT, null)),
                Arguments.of("넘침", new ChargeReversalResponse(id, "POSTED", Long.MAX_VALUE, Long.MAX_VALUE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void 조건을_못_맞춘_2xx는_Invalid(String reason, ChargeReversalResponse body) {
        givenBody(body);

        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());
    }

    @Test
    void 데이터_없는_2xx는_Invalid() {
        when(feignClient.reverseCharge(any())).thenReturn(ApiResponse.success(null));

        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());
    }

    @Test
    void 본문_없는_2xx는_Invalid() {
        when(feignClient.reverseCharge(any())).thenReturn(null);

        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());
    }

    @Test
    void 원거래_되돌릴_수_없음_422는_NotReversible() {
        givenFailure(error(422, "LEDGER_ORIGINAL_NOT_REVERSIBLE"));

        assertEquals(new ChargeReversalResult.NotReversible(), reverse());
    }

    @Test
    void 계정_정지_422는_Suspended() {
        givenFailure(error(422, "LEDGER_REJECTED_WALLET_SUSPENDED"));

        assertEquals(new ChargeReversalResult.Suspended(), reverse());
    }

    @Test
    void 원거래_없음_404는_OriginalNotFound() {
        givenFailure(error(404, "LEDGER_TRANSACTION_NOT_FOUND"));

        assertEquals(new ChargeReversalResult.OriginalNotFound(), reverse());
    }

    @ParameterizedTest
    @CsvSource({"422, LEDGER_REJECTED_INSUFFICIENT_BALANCE", "422, LEDGER_INVALID_AMOUNT",
            "404, LEDGER_ORIGINAL_NOT_REVERSIBLE", "422, LEDGER_TRANSACTION_NOT_FOUND",
            "409, LEDGER_DUPLICATE_REFERENCE", "400, INVALID_INPUT", "401, UNAUTHORIZED"})
    void 그_밖의_4xx는_Invalid(int status, String code) {
        givenFailure(error(status, code));

        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());
    }

    @Test
    void 본문_없는_404_422는_Invalid() {
        givenFailure(response(404, ""));
        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());

        givenFailure(response(422, ""));
        assertInstanceOf(ChargeReversalResult.Invalid.class, reverse());
    }

    static Stream<Arguments> unknownFailures() {
        return Stream.of(
                Arguments.of("2xx 본문 해석 실패", undecodable()),
                Arguments.of("시간 초과", timeout()),
                Arguments.of("연결 실패", connectionRefused()),
                Arguments.of("상대 302", response(302, "")),
                Arguments.of("상대 500", response(500, "")),
                Arguments.of("상대 503", error(503, "COMMON_SERVICE_UNAVAILABLE")),
                Arguments.of("상대 504", response(504, "")),
                Arguments.of("요청 전송 전 실패", new EncodeException("요청 본문을 만들 수 없습니다.")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownFailures")
    void 거절이_아닌_실패는_Unknown(String reason, FeignException failure) {
        givenFailure(failure);

        assertInstanceOf(ChargeReversalResult.Unknown.class, reverse());
    }

    private void givenBody(ChargeReversalResponse body) {
        when(feignClient.reverseCharge(any())).thenReturn(ApiResponse.success(body));
    }

    private void givenFailure(FeignException failure) {
        doThrow(failure).when(feignClient).reverseCharge(any());
    }

    private ChargeReversalResult reverse() {
        return adapter.reverse(UUID.randomUUID(), AMOUNT, null);
    }
}
