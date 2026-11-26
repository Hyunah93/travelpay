package com.travelpay.wallet.infra.persistence.correction;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrection;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionRepository;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ChargeReversalCorrectionRepositoryImpl implements ChargeReversalCorrectionRepository {

    private final ChargeReversalCorrectionJpaRepository jpaRepository;

    @Override
    public int registerIfAbsent(UUID chargeId, UUID reconciliationResultId, Instant now) {
        return jpaRepository.insertIfAbsent(UUID.randomUUID(), chargeId, reconciliationResultId, now);
    }

    @Override
    public List<ChargeReversalCorrectionTarget> findDueTargets(int limit) {
        return jpaRepository.findDueTargets(limit).stream()
                .map(row -> new ChargeReversalCorrectionTarget(row.getId(), row.getChargeId(), row.getAttemptCount(),
                        row.getUpdatedAt()))
                .toList();
    }

    @Override
    public Optional<Instant> claim(UUID id, Instant updatedAt, Duration lease) {
        List<ChargeReversalClaimRow> rows = jpaRepository.claim(id, updatedAt, lease.toSeconds());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst().getUpdatedAt());
    }

    @Override
    public int recordSucceeded(UUID id, Instant token, UUID ledgerTxId, long recoveredAmount, long receivableAmount) {
        return jpaRepository.recordSucceeded(id, token, ledgerTxId, recoveredAmount, receivableAmount);
    }

    @Override
    public int recordNeedsReview(UUID id, Instant token, int attemptCount, String lastError) {
        return jpaRepository.recordNeedsReview(id, token, attemptCount, lastError);
    }

    @Override
    public int recordRetry(UUID id, Instant token, int attemptCount, Duration delay, String lastError) {
        return jpaRepository.recordRetry(id, token, attemptCount, delay.toSeconds(), lastError);
    }

    @Override
    public long countByStatus(ChargeReversalCorrectionStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public Page<ChargeReversalCorrection> findAll(ChargeReversalCorrectionStatus status, Pageable pageable) {
        return status == null ? jpaRepository.findAll(pageable) : jpaRepository.findByStatus(status, pageable);
    }
}
