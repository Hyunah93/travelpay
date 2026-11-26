package com.travelpay.wallet.application.correction.dto;

import java.util.UUID;

/** 원장 충전 취소 분개 요청 결과. 예외로 알리지 않고 결과 종류로 나눈다 */
public sealed interface ChargeReversalResult {

    /** 201 새로 기록 / 200 이미 있음. recovered(지갑 회수) + receivable(미수) = 충전 금액 */
    record Reversed(UUID transactionId, long recoveredAmount, long receivableAmount) implements ChargeReversalResult {
    }

    /** 422 LEDGER_ORIGINAL_NOT_REVERSIBLE: 원거래가 POSTED가 아니거나 충전 구조가 아님 */
    record NotReversible() implements ChargeReversalResult {
    }

    /** 422 LEDGER_REJECTED_WALLET_SUSPENDED: 회원 지갑·시스템 계정 중 ACTIVE가 아닌 계정이 있음 */
    record Suspended() implements ChargeReversalResult {
    }

    /** 404 LEDGER_TRANSACTION_NOT_FOUND: 원거래 (CHARGE, chargeId) 없음 */
    record OriginalNotFound() implements ChargeReversalResult {
    }

    /** 그 밖의 4xx·2xx 응답 내용 이상: 다시 보내도 풀리지 않음. cause는 사유(응답 원문 없음) */
    record Invalid(String cause) implements ChargeReversalResult {
    }

    /** 4xx가 아닌 실패(3xx·5xx·시간 초과·연결 실패·2xx 본문 해석 실패): 원장이 기록했을 수 있음 */
    record Unknown(String errorClass) implements ChargeReversalResult {
    }
}
