package com.travelpay.wallet.application.correction;

/**
 * 이중 지급 정정 last_error의 분류 코드. last_error는 "코드: 짧은 설명"이며 응답 원문·계좌 정보는 넣지 않는다.
 * LEDGER_UNKNOWN만 PENDING(재시도 대기)에 남고 나머지는 NEEDS_REVIEW 사유다
 */
public enum CorrectionErrorCode {
    /** 충전이 COMPLETED·REFUNDED가 아님 (또는 충전 없음) */
    CHARGE_STATUS,
    /** 은행 환불 SUCCEEDED 없음 */
    NO_REFUND,
    /** 원장 적립(CHARGE) POSTED 없음 */
    NO_CREDIT,
    /** 환불·적립은 있으나 금액·통화·지갑이 충전과 다름 */
    AMOUNT_MISMATCH,
    /** 이미 있는 취소 분개의 구조 이상 */
    REVERSAL_MALFORMED,
    /** 원장 422 LEDGER_ORIGINAL_NOT_REVERSIBLE */
    NOT_REVERSIBLE,
    /** 원장 422 LEDGER_REJECTED_WALLET_SUSPENDED */
    WALLET_SUSPENDED,
    /** 원장 404 LEDGER_TRANSACTION_NOT_FOUND */
    ORIGINAL_NOT_FOUND,
    /** 원장 그 밖의 4xx·응답 내용 이상 */
    INVALID_RESPONSE,
    /** 원장 결과 모름 (재시도 대기 중, PENDING) */
    LEDGER_UNKNOWN,
    /** 결과 모름 5회 */
    RETRY_EXHAUSTED;

    private static final int LAST_ERROR_MAX = 500;

    /** last_error 값: "코드: 설명" (500자 이하) */
    public String withDetail(String detail) {
        String value = detail == null || detail.isBlank() ? name() : name() + ": " + detail;
        return value.length() <= LAST_ERROR_MAX ? value : value.substring(0, LAST_ERROR_MAX);
    }
}
