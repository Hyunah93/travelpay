package com.travelpay.ledger.application.integrity;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.application.integrity.dto.IntegrityCheckResult;
import com.travelpay.ledger.application.integrity.dto.IntegrityCheckResult.CheckOutcome;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.integrity.LedgerIntegrityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 원장 내부 정합성 검사. 찾기만 하고 고치지 않는다.
 * <ul>
 *     <li>읽기 전용 트랜잭션. timeout은 거래 처리(3초)와 별도 설정값(travelpay.ledger.integrity.timeout-seconds)</li>
 *     <li>격리 수준은 지정하지 않는다(Hikari의 READ COMMITTED). 검사마다 SQL 한 문장이라 한 검사 안에서는 같은 시점을 읽는다</li>
 *     <li>동시 실행은 트랜잭션 advisory lock을 기다리지 않고 시도해 막는다. 못 잡으면 409, 트랜잭션이 끝나면 자동으로 풀린다</li>
 * </ul>
 */
@Service
public class LedgerIntegrityService {

    private final LedgerIntegrityRepository integrityRepository;
    private final int violationLimit;

    public LedgerIntegrityService(LedgerIntegrityRepository integrityRepository,
                                  @Value("${travelpay.ledger.integrity.violation-limit}") int violationLimit) {
        if (violationLimit < 1) {
            throw new IllegalArgumentException("travelpay.ledger.integrity.violation-limit은 1 이상이어야 합니다.");
        }
        this.integrityRepository = integrityRepository;
        this.violationLimit = violationLimit;
    }

    @Transactional(readOnly = true, timeoutString = "${travelpay.ledger.integrity.timeout-seconds}")
    public IntegrityCheckResult check() {
        if (!integrityRepository.tryLockForCheck()) {
            throw new BusinessException(LedgerErrorCode.LEDGER_INTEGRITY_CHECK_IN_PROGRESS);
        }
        Instant checkedAt = Instant.now();
        return new IntegrityCheckResult(checkedAt, violationLimit,
                CheckOutcome.from(integrityRepository.checkAccountBalances(violationLimit)),
                CheckOutcome.from(integrityRepository.checkPostedTransactionBalances(violationLimit)),
                CheckOutcome.from(integrityRepository.checkRejectedTransactionsHaveNoEntries(violationLimit)),
                CheckOutcome.from(integrityRepository.checkCurrencyTotals(violationLimit)));
    }
}
