package com.travelpay.wallet.infra.persistence.correction;

import java.time.Instant;
import java.util.UUID;

/** 정정 대상 조회 네이티브 쿼리 프로젝션 */
public interface ChargeReversalCorrectionRow {

    UUID getId();

    UUID getChargeId();

    int getAttemptCount();

    Instant getUpdatedAt();
}
