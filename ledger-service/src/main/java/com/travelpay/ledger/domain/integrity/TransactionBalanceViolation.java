package com.travelpay.ledger.domain.integrity;

import com.travelpay.ledger.domain.Currency;

import java.math.BigInteger;
import java.util.UUID;

/** POSTED 거래의 한 통화에서 DEBIT 합과 CREDIT 합이 다른 경우 */
public record TransactionBalanceViolation(UUID transactionId, Currency currency,
                                          BigInteger debitSum, BigInteger creditSum) {
}
