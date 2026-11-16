package com.travelpay.ledger.domain.integrity;

import com.travelpay.ledger.domain.Currency;

import java.math.BigInteger;

/** 전체 계정 잔액 합이 0이 아닌 통화 */
public record CurrencyTotalViolation(Currency currency, BigInteger totalBalance) {
}
