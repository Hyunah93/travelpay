package com.travelpay.ledger.domain.integrity;

import com.travelpay.ledger.domain.Currency;

import java.math.BigInteger;
import java.util.UUID;

/** 계정 잔액과 POSTED 항목 순합계(CREDIT - DEBIT)가 다른 계정. 합계는 bigint 범위를 넘을 수 있어 BigInteger */
public record AccountBalanceViolation(UUID accountId, Currency currency, long balance, BigInteger postedNet) {
}
