package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService.EndReason;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService.RecoveryCycle;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService.RecoveryResult;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderLedgerProcessor ledgerProcessor = mock(ExchangeOrderLedgerProcessor.class);
    private final ExchangeOrderRecoveryService service = new ExchangeOrderRecoveryService(orderRepository,
            ledgerProcessor, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    void processesEachTargetAndCountsOutcome() {
        List<ExchangeOrder> targets = orders(3);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(targets.get(0))).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        when(ledgerProcessor.process(targets.get(1))).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));
        when(ledgerProcessor.process(targets.get(2))).thenReturn(withStatus(ExchangeOrderStatus.MANUAL_REVIEW));

        assertEquals(new RecoveryResult(3, 2, 1, 0), service.recover(10));
    }

    @Test
    void stopsAfterThreeConsecutiveUnsettled() {
        List<ExchangeOrder> targets = orders(5);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));

        RecoveryResult result = service.recover(10);

        assertEquals(new RecoveryResult(5, 0, 3, 0), result);
        verify(ledgerProcessor, times(3)).process(any());
    }

    @Test
    void settledOrderResetsConsecutiveCount() {
        List<ExchangeOrder> targets = orders(5);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        ExchangeOrder processing = withStatus(ExchangeOrderStatus.PROCESSING);
        when(ledgerProcessor.process(any())).thenReturn(processing, processing,
                withStatus(ExchangeOrderStatus.COMPLETED), processing, processing);

        assertEquals(new RecoveryResult(5, 1, 4, 0), service.recover(10));
        verify(ledgerProcessor, times(5)).process(any());
    }

    @Test
    void errorOnOneOrderDoesNotStopOthers() {
        List<ExchangeOrder> targets = orders(2);
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(targets);
        when(ledgerProcessor.process(targets.get(0))).thenThrow(new IllegalStateException("db down"));
        when(ledgerProcessor.process(targets.get(1))).thenReturn(withStatus(ExchangeOrderStatus.FAILED));

        assertEquals(new RecoveryResult(2, 1, 0, 1), service.recover(10));
    }

    @Test
    void nothingToRecover() {
        when(orderRepository.findRecoveryTargets(NOW, 10)).thenReturn(List.of());

        assertEquals(new RecoveryResult(0, 0, 0, 0), service.recover(10));
    }

    // ---- 한 주기에 밀린 만큼 이어서 처리(#317) ----

    /** 묶음이 가득 차 있으면 쉬지 않고 다음 묶음을 가져오고, 묶음 크기보다 적게 나오면(밀린 게 없음) 주기를 끝낸다 */
    @Test
    void drainsFullBatchesUntilAShortBatch() {
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenReturn(orders(2), orders(2), orders(1));
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));

        RecoveryCycle cycle = service.recoverPending(2, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(3, 5, 5, 0, 0, EndReason.NOTHING_LEFT), cycle);
        verify(orderRepository, times(3)).findRecoveryTargets(any(), eq(2));
    }

    /** 원장이 연달아 결과를 주지 못하면(아직 장애) 다음 묶음을 가져오지 않는다. 죽은 원장을 계속 두드리지 않게 */
    @Test
    void stopsWhenLedgerIsStillUnavailable() {
        when(orderRepository.findRecoveryTargets(any(), eq(4))).thenReturn(orders(4));
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));

        RecoveryCycle cycle = service.recoverPending(4, Duration.ofSeconds(20));

        assertEquals(EndReason.LEDGER_UNAVAILABLE, cycle.endedBy());
        assertEquals(1, cycle.batches());
        verify(ledgerProcessor, times(3)).process(any());
        verify(orderRepository, times(1)).findRecoveryTargets(any(), eq(4));
    }

    /** 한 묶음에서 확정한 주문이 없으면 더 돌아도 진전이 없으므로 끝낸다(오류가 이어질 때 등) */
    @Test
    void stopsWhenABatchSettlesNothing() {
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenReturn(orders(2));
        when(ledgerProcessor.process(any())).thenThrow(new IllegalStateException("db down"));

        RecoveryCycle cycle = service.recoverPending(2, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(1, 2, 0, 0, 2, EndReason.NO_PROGRESS), cycle);
    }

    /** 밀린 게 남아도 제한 시간이 지나면 끝낸다. 스케줄러 스레드를 오래 붙잡지 않고 남은 건 다음 주기로 넘긴다 */
    @Test
    void stopsAtTheTimeBudget() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, clockOf(now));
        // 묶음 하나에 10초가 걸린다고 치고, 항상 가득 찬 묶음을 돌려준다
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenAnswer(invocation -> {
            now.set(now.get().plusSeconds(10));
            return orders(2);
        });
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));

        RecoveryCycle cycle = timed.recoverPending(2, Duration.ofSeconds(15));

        assertEquals(new RecoveryCycle(2, 4, 4, 0, 0, EndReason.TIME_BUDGET), cycle);
    }

    private static Clock clockOf(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("Asia/Seoul");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static List<ExchangeOrder> orders(int count) {
        List<ExchangeOrder> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(withStatus(ExchangeOrderStatus.PROCESSING));
        }
        return orders;
    }

    private static ExchangeOrder withStatus(ExchangeOrderStatus status) {
        Instant issuedAt = NOW.minusSeconds(120);
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(UUID.randomUUID(),
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), issuedAt);
        ExchangeOrder order = OrderFixtures.placed(quote, UUID.randomUUID(), "key-" + UUID.randomUUID());
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}
