package com.travelpay.exchange.presentation.order;

import java.time.Duration;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 결과를 모른 채 남은 환전 주문을 주기적으로 원장에 다시 보냅니다.
 * 주문별 재시도 대기(30초 × 2^확인 횟수)는 대상 조회가 거르고, 한 주기 안에서는 batch-size건씩 가져와 처리하며
 * 밀린 게 남았으면 time-budget까지 이어서 처리합니다(#317).
 *
 * 주기(fixed-delay, 기본 5초)는 원장이 정상화된 뒤 복구가 그 사실을 알아채기까지의 최대 대기입니다. 예전 30초는 한 주기에 50건만
 * 처리하던 구조에 맞춘 값이라, 처리가 약 10초로 빨라진 뒤에는 회복 후 다음 주기까지 기다리는 시간(측정 13초)이 회복 후 시간의
 * 절반을 넘었습니다. 할 일이 없으면 조회 한 번으로 끝나고 서킷이 열려 있으면 조회도 하지 않으며, 주문별 재시도 대기는 그대로라
 * 원장에 보내는 호출 수는 늘지 않습니다. 대신 원장 장애 중 반열림 시험 호출이 서킷 대기(10초)마다 일어납니다.
 *
 * 주문 적체(처리 정체·사람 확인 대기) 지표는 복구와 따로 backlog-refresh(기본 30초)마다 셉니다. 30분 넘게 밀린 주문을 세는
 * 지표라 복구처럼 자주 셀 필요가 없고, 복구가 오래 걸려도(최대 time-budget) 지표가 늦어지지 않습니다.
 * exchange.order.recovery.enabled=false이면 둘 다 돌지 않습니다. (테스트·수동 확인용)
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "exchange.order.recovery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ExchangeOrderRecoveryScheduler {

    private final ExchangeOrderRecoveryService recoveryService;
    private final ExchangeOrderBacklogMonitor backlogMonitor;
    private final int batchSize;
    private final Duration timeBudget;

    /** 배치 크기가 1보다 작거나 제한 시간이 0 이하이면 서버 시작 단계에서 실패합니다. */
    public ExchangeOrderRecoveryScheduler(ExchangeOrderRecoveryService recoveryService,
                                          ExchangeOrderBacklogMonitor backlogMonitor,
                                          @Value("${exchange.order.recovery.batch-size:50}") int batchSize,
                                          @Value("${exchange.order.recovery.time-budget:20s}") Duration timeBudget) {
        if (batchSize < 1) {
            throw new IllegalStateException("exchange.order.recovery.batch-size는 1 이상이어야 합니다: " + batchSize);
        }
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalStateException("exchange.order.recovery.time-budget은 0보다 커야 합니다: " + timeBudget);
        }
        this.recoveryService = recoveryService;
        this.backlogMonitor = backlogMonitor;
        this.batchSize = batchSize;
        this.timeBudget = timeBudget;
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남깁니다. */
    @Scheduled(initialDelayString = "${exchange.order.recovery.fixed-delay:5s}",
            fixedDelayString = "${exchange.order.recovery.fixed-delay:5s}")
    public void recover() {
        try {
            recoveryService.recoverPending(batchSize, timeBudget);
        } catch (RuntimeException e) {
            log.error("환전 주문 복구 주기 실패: exception={}", e.getClass().getName(), e);
        }
    }

    /** 주문 적체 수를 세어 지표를 갱신합니다. 세기가 실패하면 이전 값을 유지하고 예외를 던지지 않습니다. */
    @Scheduled(initialDelayString = "${exchange.order.recovery.backlog-refresh:30s}",
            fixedDelayString = "${exchange.order.recovery.backlog-refresh:30s}")
    public void refreshBacklog() {
        backlogMonitor.refresh();
    }
}
