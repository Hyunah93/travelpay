package com.travelpay.wallet.infra.persistence.correction;

import java.time.Instant;

/** 선점 UPDATE ... RETURNING 프로젝션 */
public interface ChargeReversalClaimRow {

    Instant getUpdatedAt();
}
