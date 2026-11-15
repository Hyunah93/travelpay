package com.travelpay.ledger.domain.integrity;

import java.util.UUID;

/** 항목이 있는 REJECTED 거래 */
public record RejectedEntriesViolation(UUID transactionId, long entryCount) {
}
