package com.travelpay.ledger.application.transaction.dto;

import java.util.UUID;

/** 충전 취소 분개 요청. 금액·항목·userId는 받지 않고 원거래 (CHARGE, chargeId)와 잠근 잔액으로 정한다 */
public record ChargeReversalCommand(UUID chargeId, String description) {
}
