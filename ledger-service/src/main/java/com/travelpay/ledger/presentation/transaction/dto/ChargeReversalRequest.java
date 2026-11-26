package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.ChargeReversalCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 충전 취소 분개 요청. 금액·항목·userId는 받지 않는다 (원거래와 잠근 잔액으로 정함) */
public record ChargeReversalRequest(
        @NotNull(message = "충전 ID를 입력해 주세요.") UUID chargeId,
        @Size(max = 255, message = "설명은 255자 이하로 입력해 주세요.") String description) {

    public ChargeReversalCommand toCommand() {
        return new ChargeReversalCommand(chargeId, description);
    }
}
