package com.travelpay.wallet.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * 원장 충전 취소 분개 응답에서 지갑이 쓰는 필드. status는 문자열로, 금액은 누락을 알 수 있도록 Long으로 받는다.
 * 그 밖의 필드(entries 등)는 읽지 않는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeReversalResponse(UUID transactionId, String status, Long recoveredAmount,
                                     Long receivableAmount) {
}
