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
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeOrderLedgerProcessor ledgerProcessor = mock(ExchangeOrderLedgerProcessor.class);
    private final LedgerClient ledgerClient = mock(LedgerClient.class);
    private final ExchangeOrderRecoveryService service = new ExchangeOrderRecoveryService(orderRepository,
            ledgerProcessor, ledgerClient, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    /** 기본은 원장에 보낼 수 있는 상태(서킷 닫힘) */
    @BeforeEach
    void ledgerAvailable() {
        when(ledgerClient.isAvailable()).thenReturn(true);
    }

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

        // 원장이 결과를 줬으므로 끝내기 전에 대기와 관계없는 조회를 한 번 더 한다(남은 게 없어 0건)
        assertEquals(new RecoveryCycle(4, 5, 5, 0, 0, EndReason.NOTHING_LEFT, true), cycle);
        verify(orderRepository, times(3)).findRecoveryTargets(any(), eq(2));
        verify(orderRepository, times(1)).findNotCheckedSince(NOW, 2);
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

        assertEquals(new RecoveryCycle(1, 2, 0, 0, 2, EndReason.NO_PROGRESS, false), cycle);
    }

    /** 밀린 게 남아도 제한 시간이 지나면 끝낸다. 스케줄러 스레드를 오래 붙잡지 않고 남은 건 다음 주기로 넘긴다 */
    @Test
    void stopsAtTheTimeBudget() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, ledgerClient, clockOf(now));
        // 묶음 하나에 10초가 걸린다고 치고, 항상 가득 찬 묶음을 돌려준다
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenAnswer(invocation -> {
            now.set(now.get().plusSeconds(10));
            return orders(2);
        });
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));

        RecoveryCycle cycle = timed.recoverPending(2, Duration.ofSeconds(15));

        // 두 번째 묶음은 고른 시점(20초)에 이미 제한 시간이 지나 한 건도 보내지 않는다
        assertEquals(new RecoveryCycle(2, 4, 2, 0, 0, EndReason.TIME_BUDGET, false), cycle);
        verify(ledgerProcessor, times(2)).process(any());
    }

    /**
     * 제한 시간은 주문을 보내기 전마다 확인한다. 원장이 느린 호출 기준(2초)보다 조금 빠르게(1.9초) 성공하면 서킷은 열리지 않는데,
     * 묶음이 끝날 때만 확인하면 50건 묶음이 약 95초 동안 스케줄러 스레드를 붙잡는다(PR #341 리뷰)
     */
    @Test
    void stopsWithinABatchOnceTheTimeBudgetPasses() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, ledgerClient, clockOf(now));
        when(orderRepository.findRecoveryTargets(any(), eq(50))).thenReturn(orders(50));
        when(ledgerProcessor.process(any())).thenAnswer(invocation -> {
            now.set(now.get().plusMillis(1_900));
            return withStatus(ExchangeOrderStatus.COMPLETED);
        });

        RecoveryCycle cycle = timed.recoverPending(50, Duration.ofSeconds(20));

        // 0, 1.9, … 19.0초에 보낸 11건까지 처리하고, 20.9초가 된 다음 주문 앞에서 멈춘다
        assertEquals(new RecoveryCycle(1, 50, 11, 0, 0, EndReason.TIME_BUDGET, false), cycle);
        verify(ledgerProcessor, times(11)).process(any());
    }

    // ---- 서킷이 열려 있으면 고르지도 않음(#317) ----

    /** 서킷이 열려 원장에 보낼 수 없으면 주문을 고르지 않는다. 선점하지 않으니 확인 횟수(재시도 대기)가 오르지 않는다 */
    @Test
    void skipsTheCycleWhileTheCircuitIsOpen() {
        when(ledgerClient.isAvailable()).thenReturn(false);

        RecoveryCycle cycle = service.recoverPending(50, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(1, 0, 0, 0, 0, EndReason.LEDGER_UNAVAILABLE, false), cycle);
        verify(orderRepository, never()).findRecoveryTargets(any(), anyInt());
        verify(ledgerProcessor, never()).process(any());
    }

    /** 처리 도중 서킷이 열리면 남은 주문은 건드리지 않고 멈춘다(남은 주문의 확인 횟수가 오르지 않음) */
    @Test
    void stopsWhenTheCircuitOpensMidBatch() {
        List<ExchangeOrder> targets = orders(3);
        when(orderRepository.findRecoveryTargets(any(), eq(3))).thenReturn(targets);
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        // 주기 시작 전 확인, 첫 주문 전 확인은 보낼 수 있음. 두 번째 주문 전에 서킷이 열림
        when(ledgerClient.isAvailable()).thenReturn(true, true, false);

        RecoveryCycle cycle = service.recoverPending(3, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(1, 3, 1, 0, 0, EndReason.LEDGER_UNAVAILABLE, false), cycle);
        verify(ledgerProcessor, times(1)).process(any());
    }

    // ---- 원장이 살아 있으면 늘어난 재시도 대기를 기다리지 않음(#317) ----

    /**
     * 대기가 지난 주문을 처리하다 원장이 결과를 주면(살아 있음), 대기가 남은 주문(장애 중 헛걸음한 꼬리)도 이번 주기에 처리한다.
     * 원장이 결과를 주기 시작한 뒤에 보낸 주문은 고르지 않도록, 처음 확정된 주문을 보낸 시각을 기준으로 조회한다
     */
    @Test
    void processesOrdersStillWaitingOnceTheLedgerAnswers() {
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenReturn(orders(1));
        when(orderRepository.findNotCheckedSince(NOW, 2)).thenReturn(orders(2), List.of());
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));

        RecoveryCycle cycle = service.recoverPending(2, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(3, 3, 3, 0, 0, EndReason.NOTHING_LEFT, true), cycle);
        verify(orderRepository, times(2)).findNotCheckedSince(NOW, 2);
        verify(ledgerProcessor, times(3)).process(any());
    }

    /**
     * 장애 끝자락에 보낸 주문(5초 기다리다 시간 초과)은 원장이 결과를 주기 전에 보낸 것이라, 원장이 살아난 걸 확인하면 같은 주기에
     * 다시 보낼 대상이 된다. 주기 시작 시각이 아니라 처음 확정된 주문을 보낸 시각(시간 초과 뒤)을 기준으로 조회한다.
     * 주기 시작 시각을 기준으로 하면 이 주문은 "이번 주기에 이미 보낸 주문"으로 빠져 늘어난 재시도 대기(약 2분)를 기다렸다(#317 v2 측정)
     */
    @Test
    void retriesOrderSentBeforeTheLedgerAnsweredInTheSameCycle() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, ledgerClient, clockOf(now));
        List<ExchangeOrder> targets = orders(2);
        when(orderRepository.findRecoveryTargets(any(), eq(5))).thenReturn(targets);
        // 첫 주문: 장애 끝자락에 보내 5초 기다리다 시간 초과(PROCESSING으로 남음). 그사이 원장이 회복됨
        when(ledgerProcessor.process(targets.get(0))).thenAnswer(invocation -> {
            now.set(now.get().plusSeconds(5));
            return withStatus(ExchangeOrderStatus.PROCESSING);
        });
        // 둘째 주문: 원장이 바로 결과를 줌
        when(ledgerProcessor.process(targets.get(1))).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        Instant ledgerAnsweredFrom = NOW.plusSeconds(5);
        ExchangeOrder tail = withStatus(ExchangeOrderStatus.PROCESSING);
        when(orderRepository.findNotCheckedSince(ledgerAnsweredFrom, 5)).thenReturn(List.of(tail));
        when(ledgerProcessor.process(tail)).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));

        RecoveryCycle cycle = timed.recoverPending(5, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(2, 3, 2, 1, 0, EndReason.NOTHING_LEFT, true), cycle);
        verify(orderRepository).findNotCheckedSince(ledgerAnsweredFrom, 5);
        verify(orderRepository, never()).findNotCheckedSince(NOW, 5);
    }

    /**
     * 처리할 양이 적으면 같은 주기 마무리 단계가 장애 끝자락 주문의 선점(10초)이 풀리기 전에 끝난다. 다음 주기에는 보낼 주문이 없어
     * 원장이 결과를 주지 않지만, 직전 주기의 증거(원장이 처음 결과를 준 시각)로 마무리 단계를 돌아 그 주문을 다시 보낸다(#317 v4).
     * v3까지는 이 주기에 증거가 없어 마무리 단계를 돌지 않았고, 그 주문이 재시도 대기(약 2분)를 기다렸다(v3 300건 측정)
     */
    @Test
    void sweepsInTheNextCycleWithTheLastCycleLedgerAnswer() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, ledgerClient, clockOf(now));
        // 주기 1: 원장이 결과를 줌(NOW에 보낸 주문이 확정). 장애 끝자락 주문은 선점이 남아 마무리 단계에서 확정되지 못함
        ExchangeOrder blocked = withStatus(ExchangeOrderStatus.PROCESSING);
        when(orderRepository.findRecoveryTargets(any(), eq(5))).thenReturn(orders(1), List.of());
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        when(ledgerProcessor.process(blocked)).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING),
                withStatus(ExchangeOrderStatus.COMPLETED));
        when(orderRepository.findNotCheckedSince(NOW, 5)).thenReturn(List.of(blocked));

        RecoveryCycle first = timed.recoverPending(5, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(2, 2, 1, 1, 0, EndReason.NOTHING_LEFT, true), first);

        // 주기 2(5초 뒤): 재시도 대기가 끝난 주문이 없어 원장에 보낼 게 없지만, 직전 주기의 증거로 마무리 단계를 돈다
        now.set(NOW.plusSeconds(5));
        RecoveryCycle second = timed.recoverPending(5, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(2, 1, 1, 0, 0, EndReason.NOTHING_LEFT, true), second);
        verify(orderRepository, times(2)).findNotCheckedSince(NOW, 5);
    }

    /**
     * 직전 증거는 원장이 처음 결과를 준 시각 + 선점 유효 시간(10초) 뒤에 시작한 주기까지만 쓴다. 그 주기에는 그보다 먼저 선점된
     * 주문의 선점이 모두 풀려 있으므로 마무리 단계를 한 번 더 돌 이유가 없다. 원장이 계속 결과를 주지 않으면 평소처럼 재시도 대기를 따른다
     */
    @Test
    void forgetsTheLedgerAnswerOnceTheClaimLeaseHasPassed() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        ExchangeOrderRecoveryService timed = new ExchangeOrderRecoveryService(orderRepository, ledgerProcessor, ledgerClient, clockOf(now));
        when(orderRepository.findRecoveryTargets(any(), eq(5))).thenReturn(orders(1), List.of());
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.COMPLETED));
        when(orderRepository.findNotCheckedSince(any(), eq(5))).thenReturn(List.of());
        timed.recoverPending(5, Duration.ofSeconds(20));

        // 증거(NOW) + 10초에 시작한 주기: 마지막으로 마무리 단계를 돈다
        now.set(NOW.plus(ExchangeOrderLedgerProcessor.CLAIM_LEASE));
        assertEquals(true, timed.recoverPending(5, Duration.ofSeconds(20)).backoffSkipped());

        // 그다음 주기: 증거가 없어 마무리 단계를 돌지 않는다
        now.set(NOW.plus(ExchangeOrderLedgerProcessor.CLAIM_LEASE).plusSeconds(5));
        assertEquals(false, timed.recoverPending(5, Duration.ofSeconds(20)).backoffSkipped());
        verify(orderRepository, times(2)).findNotCheckedSince(NOW, 5);
    }

    /** 이번 주기에 원장이 결과를 준 적이 없으면(살아 있는지 모름) 대기를 무시하지 않는다. 죽은 원장을 두드리지 않게 */
    @Test
    void keepsWaitingWhenTheLedgerHasNotAnswered() {
        when(orderRepository.findRecoveryTargets(any(), eq(2))).thenReturn(orders(1));
        when(ledgerProcessor.process(any())).thenReturn(withStatus(ExchangeOrderStatus.PROCESSING));

        RecoveryCycle cycle = service.recoverPending(2, Duration.ofSeconds(20));

        assertEquals(new RecoveryCycle(1, 1, 0, 1, 0, EndReason.NOTHING_LEFT, false), cycle);
        verify(orderRepository, never()).findNotCheckedSince(any(), anyInt());
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
