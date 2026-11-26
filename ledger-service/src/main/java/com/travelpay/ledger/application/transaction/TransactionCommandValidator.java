package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.ReferenceType;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 형식 검증(400). DB에 닿기 전에 확인하며, 위반이면 아무것도 기록하지 않는다.
 * 컨트롤러의 Bean Validation과 일부 겹치지만, 다른 경로로 들어온 명령도 막기 위해 서비스에서 다시 확인한다.
 */
final class TransactionCommandValidator {

    private static final int MIN_ENTRIES = 2;
    private static final int MAX_ENTRIES = 20;
    /**
     * 충전 취소 분개 API만 기록하는 항목 유형. 거래 등록 API로 미수 계정에 임의 금액을 기록하지 못하게 거절한다
     */
    private static final Set<EntryType> CHARGE_REVERSAL_ENTRY_TYPES = Set.of(
            EntryType.CHARGE_REVERSAL_OUT, EntryType.CHARGE_REVERSAL_IN, EntryType.CHARGE_REVERSAL_RECEIVABLE);
    /**
     * 거래 등록 API가 받는 항목 유형. CHECK 값 중 충전 취소 분개 전용 값을 뺀 것.
     * 허용 밖 값이 DB CHECK(500)까지 내려가지 않게 미리 거절한다
     */
    private static final Set<String> ENTRY_TYPES = Arrays.stream(EntryType.values())
            .filter(entryType -> !CHARGE_REVERSAL_ENTRY_TYPES.contains(entryType))
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private TransactionCommandValidator() {
    }

    static void validate(RegisterTransactionCommand command) {
        // CHARGE_REVERSAL은 충전 취소 분개 API만 기록한다
        if (command.referenceType() == null || command.referenceType() == ReferenceType.CHARGE_REVERSAL
                || command.referenceId() == null
                || command.entries().size() < MIN_ENTRIES || command.entries().size() > MAX_ENTRIES) {
            throw invalid();
        }
        Map<Currency, Long> debits = new EnumMap<>(Currency.class);
        Map<Currency, Long> credits = new EnumMap<>(Currency.class);
        for (RegisterTransactionCommand.Entry entry : command.entries()) {
            validateEntry(entry);
            Map<Currency, Long> sums = entry.direction() == Direction.DEBIT ? debits : credits;
            sums.merge(entry.currency(), entry.amount(), TransactionCommandValidator::addExact);
        }
        if (!debits.equals(credits)) {
            throw invalid();
        }
    }

    private static void validateEntry(RegisterTransactionCommand.Entry entry) {
        if (entry == null || entry.ownerType() == null || entry.currency() == null
                || entry.direction() == null || entry.amount() <= 0
                || entry.entryType() == null || !ENTRY_TYPES.contains(entry.entryType())) {
            throw invalid();
        }
        boolean validAccount = switch (entry.ownerType()) {
            case USER -> entry.walletId() != null && entry.accountType() == null;
            // 미수(RECEIVABLE)는 충전 취소 분개 API만 기록한다
            case SYSTEM -> entry.walletId() == null && entry.accountType() != null
                    && entry.accountType() != AccountType.RECEIVABLE;
            // 가맹점 계정 지정 방식은 MVP 이후 결정
            case MERCHANT -> false;
        };
        if (!validAccount) {
            throw invalid();
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST);
    }
}
