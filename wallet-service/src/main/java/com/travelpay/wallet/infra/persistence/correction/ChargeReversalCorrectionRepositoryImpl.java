package com.travelpay.wallet.infra.persistence.correction;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ChargeReversalCorrectionRepositoryImpl implements ChargeReversalCorrectionRepository {

    private final ChargeReversalCorrectionJpaRepository jpaRepository;

    @Override
    public int registerIfAbsent(UUID chargeId, UUID reconciliationResultId, Instant now) {
        return jpaRepository.insertIfAbsent(UUID.randomUUID(), chargeId, reconciliationResultId, now);
    }
}
