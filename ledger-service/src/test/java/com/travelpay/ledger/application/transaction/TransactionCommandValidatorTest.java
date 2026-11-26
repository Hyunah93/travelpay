package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 거래 등록 명령 형식 검증 (DB 없이).
 * 충전 취소 분개 전용 값(CHARGE_REVERSAL 참조 유형, CHARGE_REVERSAL_* 항목 유형, RECEIVABLE 계정)은
 * 충전 취소 분개 API만 기록하므로 거래 등록 API에서는 400으로 거절한다.
 */
class TransactionCommandValidatorTest {

    private static final long AMOUNT = 1_000L;
    private static final UUID WALLET_ID = UUID.randomUUID();

    @Test
    void 충전_자금원에서_지갑으로_가는_정상_명령은_통과한다() {
        assertDoesNotThrow(() -> TransactionCommandValidator.validate(
                charge(ReferenceType.CHARGE, AccountType.CHARGE_SOURCE, EntryType.CHARGE_IN.name())));
    }

    @ParameterizedTest
    @EnumSource(value = EntryType.class,
            names = {"CHARGE_REVERSAL_OUT", "CHARGE_REVERSAL_IN", "CHARGE_REVERSAL_RECEIVABLE"},
            mode = EnumSource.Mode.EXCLUDE)
    void 충전_취소_분개_전용이_아닌_항목_유형은_통과한다(EntryType entryType) {
        assertDoesNotThrow(() -> TransactionCommandValidator.validate(
                charge(ReferenceType.CHARGE, AccountType.CHARGE_SOURCE, entryType.name())));
    }

    @ParameterizedTest
    @EnumSource(value = EntryType.class,
            names = {"CHARGE_REVERSAL_OUT", "CHARGE_REVERSAL_IN", "CHARGE_REVERSAL_RECEIVABLE"})
    void 충전_취소_분개_항목_유형은_400이다(EntryType entryType) {
        assertInvalid(charge(ReferenceType.CHARGE, AccountType.CHARGE_SOURCE, entryType.name()));
    }

    @Test
    void CHARGE_REVERSAL_참조_유형은_400이다() {
        assertInvalid(charge(ReferenceType.CHARGE_REVERSAL, AccountType.CHARGE_SOURCE, EntryType.CHARGE_IN.name()));
    }

    @Test
    void RECEIVABLE_계정_지정은_400이다() {
        assertInvalid(charge(ReferenceType.CHARGE, AccountType.RECEIVABLE, EntryType.CHARGE_IN.name()));
    }

    /** SYSTEM(accountType) DEBIT → 지갑 KRW CREDIT. 두 항목 모두 같은 entryType */
    private static RegisterTransactionCommand charge(ReferenceType referenceType, AccountType systemAccount,
                                                     String entryType) {
        return new RegisterTransactionCommand(referenceType, UUID.randomUUID(), null, null, null, List.of(
                new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, systemAccount,
                        Currency.KRW, Direction.DEBIT, AMOUNT, entryType),
                new RegisterTransactionCommand.Entry(OwnerType.USER, WALLET_ID, null,
                        Currency.KRW, Direction.CREDIT, AMOUNT, entryType)));
    }

    private static void assertInvalid(RegisterTransactionCommand command) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> TransactionCommandValidator.validate(command));
        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }
}
