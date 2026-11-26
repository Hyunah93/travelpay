package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionRepository;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 이중 지급 정정의 DB 트랜잭션 단계. 은행·원장 호출이 트랜잭션 안에 들어가지 않도록
 * 스케줄러·처리기(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다. 선점과 결과 기록은 건마다 따로 커밋한다
 */
@Component
@RequiredArgsConstructor
public class DoublePaymentCorrectionSteps {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초). 다른 Steps와 같은 값 */
    static final int TX_TIMEOUT_SECONDS = 3;

    /**
     * 선점 임대: 선점할 때 next_attempt_at을 now() + 30초로 미뤄, 처리 중인 행을 다음 주기 조회가 다시 집지 않게 한다.
     * 30초는 충전·송금 복구의 최소 재선택 간격(payment-transfer.md D5 첫 간격 30초)과 같은 값이다.
     * 처리가 이보다 오래 걸려 임대가 끝나면 다른 처리기가 다시 선점할 수 있고, 그때는 선점 토큰이 늦은 기록을 막는다
     */
    public static final Duration LEASE = Duration.ofSeconds(30);

    private final ChargeReversalCorrectionRepository correctionRepository;
    private final ChargeRequestRepository chargeRequestRepository;

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public List<ChargeReversalCorrectionTarget> findDueTargets(int limit) {
        return correctionRepository.findDueTargets(limit);
    }

    /** 조회 이후 아무도 바꾸지 않았으면 선점하고 선점 토큰(바뀐 updated_at), 다른 처리기가 먼저 바꿨으면 empty */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public Optional<Instant> claim(ChargeReversalCorrectionTarget target) {
        return correctionRepository.claim(target.id(), target.updatedAt(), LEASE);
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeRequest> findCharge(UUID chargeId) {
        return chargeRequestRepository.findById(chargeId);
    }

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public boolean recordSucceeded(UUID id, Instant token, UUID ledgerTxId, long recovered, long receivable) {
        return correctionRepository.recordSucceeded(id, token, ledgerTxId, recovered, receivable) == 1;
    }

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public boolean recordNeedsReview(UUID id, Instant token, int attemptCount, String lastError) {
        return correctionRepository.recordNeedsReview(id, token, attemptCount, lastError) == 1;
    }

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public boolean recordRetry(UUID id, Instant token, int attemptCount, Duration delay, String lastError) {
        return correctionRepository.recordRetry(id, token, attemptCount, delay, lastError) == 1;
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public long countByStatus(ChargeReversalCorrectionStatus status) {
        return correctionRepository.countByStatus(status);
    }
}
