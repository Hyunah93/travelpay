package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.correction.LedgerChargeReversalClient;
import com.travelpay.wallet.application.correction.dto.ChargeReversalResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 원장 충전 취소 분개 응답 해석. 거래 등록(LedgerTransactionClientAdapter.classify)과 따로 분류한다.
 * 원장 응답 내용은 결과·로그에 싣지 않는다 (4xx의 error.code만 사용). 타임아웃·재시도는 같은 Feign 클라이언트
 * (contextId ledger-transaction, 재시도 끔) 설정을 그대로 쓴다.
 * - 201·200 + transactionId + status POSTED + 회수·미수 금액 0 이상 + 합 = 충전 금액: Reversed (그 밖의 2xx는 Invalid)
 * - 422 LEDGER_ORIGINAL_NOT_REVERSIBLE: NotReversible / 422 LEDGER_REJECTED_WALLET_SUSPENDED: Suspended
 * - 404 LEDGER_TRANSACTION_NOT_FOUND: OriginalNotFound
 * - 그 밖의 4xx (코드가 다른 404·422 포함): Invalid
 * - 4xx가 아닌 모든 실패(3xx·5xx·시간 초과·연결 실패·2xx 본문 해석 실패·요청 전송 전 실패): Unknown
 */
@Component
@RequiredArgsConstructor
public class LedgerChargeReversalClientAdapter implements LedgerChargeReversalClient {

    private static final int NOT_FOUND = 404;
    private static final int UNPROCESSABLE = 422;
    private static final String NOT_FOUND_CODE = "LEDGER_TRANSACTION_NOT_FOUND";
    private static final String NOT_REVERSIBLE_CODE = "LEDGER_ORIGINAL_NOT_REVERSIBLE";
    private static final String SUSPENDED_CODE = "LEDGER_REJECTED_WALLET_SUSPENDED";
    private static final String POSTED = "POSTED";

    private final LedgerTransactionFeignClient feignClient;

    @Override
    public ChargeReversalResult reverse(UUID chargeId, long chargeAmount, String description) {
        ApiResponse<ChargeReversalResponse> response;
        try {
            response = feignClient.reverseCharge(new ChargeReversalRequest(chargeId, description));
        } catch (FeignException e) {
            return classify(e);
        }
        return interpret(response, chargeAmount);
    }

    private static ChargeReversalResult interpret(ApiResponse<ChargeReversalResponse> response, long chargeAmount) {
        if (response == null || !response.success() || response.data() == null) {
            return new ChargeReversalResult.Invalid("ledger response invalid: body");
        }
        ChargeReversalResponse data = response.data();
        if (data.transactionId() == null || !POSTED.equals(data.status())) {
            return new ChargeReversalResult.Invalid("ledger response invalid: transaction");
        }
        Long recovered = data.recoveredAmount();
        Long receivable = data.receivableAmount();
        if (recovered == null || receivable == null || recovered < 0 || receivable < 0) {
            return new ChargeReversalResult.Invalid("ledger response invalid: amount");
        }
        // 원장이 보장하는 조건(회수 + 미수 = 원거래 금액)을 지갑이 한 번 더 확인하는 방어.
        // 어긋나면 원장 기록과 지갑 충전이 다른 거래를 가리킬 수 있어 정정 완료로 보지 않는다
        // 둘 다 0 이상이라 빼기로 비교해 넘침 없이 확인한다
        if (recovered > chargeAmount || receivable != chargeAmount - recovered) {
            return new ChargeReversalResult.Invalid("ledger response invalid: amount sum");
        }
        return new ChargeReversalResult.Reversed(data.transactionId(), recovered, receivable);
    }

    private static ChargeReversalResult classify(FeignException e) {
        if (!FeignErrors.isRejected(e)) {
            return new ChargeReversalResult.Unknown(e.getClass().getName());
        }
        String code = FeignErrors.errorCode(e).orElse(null);
        if (e.status() == UNPROCESSABLE && NOT_REVERSIBLE_CODE.equals(code)) {
            return new ChargeReversalResult.NotReversible();
        }
        if (e.status() == UNPROCESSABLE && SUSPENDED_CODE.equals(code)) {
            return new ChargeReversalResult.Suspended();
        }
        if (e.status() == NOT_FOUND && NOT_FOUND_CODE.equals(code)) {
            return new ChargeReversalResult.OriginalNotFound();
        }
        return new ChargeReversalResult.Invalid(e.getClass().getName() + " status=" + e.status());
    }
}
