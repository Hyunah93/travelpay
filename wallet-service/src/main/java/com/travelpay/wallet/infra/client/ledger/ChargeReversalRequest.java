package com.travelpay.wallet.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** 원장 충전 취소 분개 요청. description(선택, 255자 이하)이 null이면 보내지 않는다 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeReversalRequest(UUID chargeId, String description) {
}
