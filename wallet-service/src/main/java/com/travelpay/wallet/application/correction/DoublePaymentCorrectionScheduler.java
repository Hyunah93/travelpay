package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionTarget;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 이중 지급 자동 정정. 대사가 등록한 PENDING 정정 대상 중 next_attempt_at이 지난 건을 선점해 처리기를 돌린다.
 * 활성화·주기·배치·한 주기 마감은 충전 복구 스케줄러(ChargeRecoveryScheduler)와 같은 방식·같은 기본값이다.
 * 선점은 건마다 짧은 트랜잭션으로 커밋하고, 처리기는 트랜잭션 밖에서 호출한다 (외부 호출 동안 행 잠금을 잡지 않도록).
 * 이 메서드와 배치 루프에는 @Transactional을 붙이지 않는다.
 * <p>
 * 배치가 가득 차면 한 주기 안에서 이어서 조회하고, 덜 찬 배치가 나오거나 ROUND_BUDGET이 지나면 끝낸다 (첫 배치는 항상).
 * 주기마다 NEEDS_REVIEW·PENDING 건수 지표도 갱신한다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.charge.correction", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DoublePaymentCorrectionScheduler {

    /** 추가 배치를 시작하지 않는 마감이며 실행 시간 상한이 아니다. 충전 복구와 같은 값 */
    static final Duration ROUND_BUDGET = Duration.ofSeconds(8);
    static final String NEEDS_REVIEW_METRIC = "charge.correction.needs_review";
    static final String PENDING_METRIC = "charge.correction.pending";

    private static final String BATCH_SIZE_KEY = "travelpay.wallet.charge.correction.batch-size";
    private static final String FIXED_DELAY_KEY = "travelpay.wallet.charge.correction.fixed-delay";
    private static final Duration MIN_FIXED_DELAY = Duration.ofSeconds(1);

    private final DoublePaymentCorrectionSteps steps;
    private final DoublePaymentCorrectionProcessor processor;
    private final int batchSize;
    private final LongSupplier nanoClock;
    private final AtomicLong needsReview = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();

    /** 설정이 잘못되면 IllegalStateException으로 기동을 멈춘다 (@Scheduled 등록 전) */
    @Autowired
    public DoublePaymentCorrectionScheduler(DoublePaymentCorrectionSteps steps,
                                            DoublePaymentCorrectionProcessor processor,
                                            MeterRegistry meterRegistry,
                                            @Value("${" + BATCH_SIZE_KEY + ":10}") int batchSize,
                                            @Value("${" + FIXED_DELAY_KEY + ":10s}") String fixedDelay) {
        this(steps, processor, meterRegistry, batchSize, fixedDelay, System::nanoTime);
    }

    /** 테스트에서 시간 한도를 확인하려고 시각 공급자를 받는다 */
    DoublePaymentCorrectionScheduler(DoublePaymentCorrectionSteps steps, DoublePaymentCorrectionProcessor processor,
                                     MeterRegistry meterRegistry, int batchSize, String fixedDelay,
                                     LongSupplier nanoClock) {
        if (batchSize < 1) {
            throw new IllegalStateException(BATCH_SIZE_KEY + "는 1 이상이어야 합니다: " + batchSize);
        }
        requireFixedDelay(fixedDelay);
        this.steps = steps;
        this.processor = processor;
        this.batchSize = batchSize;
        this.nanoClock = nanoClock;
        Gauge.builder(NEEDS_REVIEW_METRIC, needsReview, AtomicLong::get)
                .description("사람 확인이 필요한(NEEDS_REVIEW) 이중 지급 정정 수")
                .register(meterRegistry);
        Gauge.builder(PENDING_METRIC, pending, AtomicLong::get)
                .description("처리 대기(PENDING) 이중 지급 정정 수. 재조회 실패로 다시 시도 중인 건도 포함")
                .register(meterRegistry);
    }

    /** ChargeRecoveryScheduler와 같은 파서: "10s"·ISO-8601, 단위 없는 숫자는 밀리초 */
    private static void requireFixedDelay(String fixedDelay) {
        Duration delay;
        try {
            delay = DurationFormatterUtils.detectAndParse(fixedDelay, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(FIXED_DELAY_KEY + " 형식이 잘못되었습니다: " + fixedDelay, e);
        }
        if (delay.compareTo(MIN_FIXED_DELAY) < 0) {
            throw new IllegalStateException(FIXED_DELAY_KEY + "는 1초 이상이어야 합니다: " + fixedDelay);
        }
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.charge.correction.fixed-delay:10s}")
    public void correct() {
        drain();
        refreshMetrics();
    }

    /** 조회에 실패하면 로그만 남기고 이전 값을 유지한다 */
    private void refreshMetrics() {
        try {
            needsReview.set(steps.countByStatus(ChargeReversalCorrectionStatus.NEEDS_REVIEW));
            pending.set(steps.countByStatus(ChargeReversalCorrectionStatus.PENDING));
        } catch (RuntimeException e) {
            log.error("이중 지급 정정 건수 조회 실패 exception={}", e.getClass().getName());
        }
    }

    private void drain() {
        long deadline = nanoClock.getAsLong() + ROUND_BUDGET.toNanos();
        while (runBatch()) {
            if (nanoClock.getAsLong() >= deadline) {
                return;
            }
        }
    }

    /** 배치 하나를 처리하고, 이어서 더 처리할지(가득 찬 배치였는지) 돌려준다 */
    private boolean runBatch() {
        long startedAt = System.nanoTime();
        List<ChargeReversalCorrectionTarget> targets;
        try {
            targets = steps.findDueTargets(batchSize);
        } catch (RuntimeException e) {
            log.error("이중 지급 정정 대상 조회 실패 exception={}", e.getClass().getName());
            return false;
        }
        if (targets.isEmpty()) {
            return false;
        }
        int claimMissed = 0;
        int failed = 0;
        Map<DoublePaymentCorrectionProcessor.Outcome, Integer> outcomes =
                new EnumMap<>(DoublePaymentCorrectionProcessor.Outcome.class);
        for (ChargeReversalCorrectionTarget target : targets) {
            try {
                Optional<Instant> token = steps.claim(target);
                if (token.isEmpty()) {
                    claimMissed++;
                    continue;
                }
                outcomes.merge(processor.process(target, token.get()), 1, Integer::sum);
            } catch (RuntimeException e) {
                // 한 건의 예상 밖 예외로 같은 배치의 다른 건이 멈추지 않게 한다. 선점했으면 임대가 끝난 뒤 다시 처리된다
                failed++;
                log.error("이중 지급 정정 처리 실패 correctionId={} chargeId={} exception={}",
                        target.id(), target.chargeId(), e.getClass().getName());
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("이중 지급 정정 배치 found={} claimMissed={} failed={} outcomes={} elapsedMs={}",
                targets.size(), claimMissed, failed, outcomes, elapsedMs);
        return targets.size() >= batchSize;
    }
}
