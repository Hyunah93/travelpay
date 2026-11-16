package com.travelpay.exchange.domain.ledger;

import java.util.UUID;

/**
 * 원장 거래 등록·조회입니다. 환전은 이 인터페이스에만 의존하고, 원장 호출 방법은 infrastructure 구현체가 정합니다.
 * 구현체는 예외를 던지지 않고 모든 결과를 {@link LedgerPostResult}·{@link LedgerLookupResult}로 돌려줍니다.
 */
public interface LedgerClient {

    LedgerPostResult post(LedgerPosting posting);

    /** 주문 ID(referenceType=EXCHANGE, referenceId=orderId)로 원장 거래를 단건 조회합니다. */
    LedgerLookupResult findByOrderId(UUID orderId);

    /**
     * 지금 원장에 거래 등록을 보낼 수 있는지 돌려줍니다. 원장 장애로 보내지 않기로 한 상태(서킷 열림)면 false입니다.
     * 복구가 보내지도 못할 주문을 선점해 확인 횟수(재시도 대기)를 올리지 않게 하려고 씁니다(#317).
     * 보낼 수 있는지 판단할 방법이 없는 구현은 항상 보낼 수 있다고 봅니다.
     */
    default boolean isAvailable() {
        return true;
    }
}
