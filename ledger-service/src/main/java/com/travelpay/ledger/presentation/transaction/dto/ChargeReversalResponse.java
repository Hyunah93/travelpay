package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.ChargeReversalResult;
import com.travelpay.ledger.domain.transaction.LedgerTransactionStatus;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.domain.transaction.RejectReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 거래 응답 형식(TransactionResultResponse)과 같은 필드 + recoveredAmount(지갑에서 회수한 금액),
 * receivableAmount(미수 금액). 취소 분개는 POSTED로만 기록되므로 rejectReason은 항상 null
 */
public record ChargeReversalResponse(UUID transactionId, LedgerTransactionStatus status,
                                     RejectReason rejectReason, ReferenceType referenceType, UUID referenceId,
                                     Instant createdAt, List<LedgerEntryResponse> entries,
                                     long recoveredAmount, long receivableAmount) {

    public static ChargeReversalResponse from(ChargeReversalResult result) {
        TransactionResultResponse transaction = TransactionResultResponse.from(result.transaction());
        return new ChargeReversalResponse(transaction.transactionId(), transaction.status(),
                transaction.rejectReason(), transaction.referenceType(), transaction.referenceId(),
                transaction.createdAt(), transaction.entries(), result.recoveredAmount(), result.receivableAmount());
    }
}
