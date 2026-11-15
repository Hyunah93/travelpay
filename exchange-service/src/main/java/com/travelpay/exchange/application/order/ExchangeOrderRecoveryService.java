package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결과를 모른 채 남은 PROCESSING 주문을 원장에 다시 보냅니다. 원장은 같은 reference를 한 번만 기록하므로 다시 POST만 합니다.
 * 대상은 대기 시간(30초 × 2^확인 횟수, 최대 16분)이 지난 주문이고, 한 건씩 차례로 처리기에 넘깁니다.
 * 여러 서버가 같은 주문을 집어도 처리기의 선점으로 원장 호출은 한 번입니다.
 * 트랜잭션을 걸지 않습니다. 원장 호출 동안 DB 커넥션이나 행 잠금을 잡지 않기 위해서입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeOrderRecoveryService {

    /** 원장이 이만큼 연달아 결과를 주지 못하면 장애로 보고 남은 주문은 다음 주기로 미룬다 */
    static final int MAX_CONSECUTIVE_UNSETTLED = 3;

    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderLedgerProcessor ledgerProcessor;
    private final LedgerClient ledgerClient;
    private final Clock clock;

    /**
     * 최근 주기에 원장이 처음 결과를 준 호출을 보낸 시각. 이 시각부터 선점 유효 시간(10초)이 지난 뒤 시작한 주기까지는,
     * 그 주기에 원장이 결과를 주지 않았어도 원장이 살아 있다고 보고 마무리 단계를 돈다(#317 v4).
     * 장애 끝자락에 보낸 주문은 이 시각보다 먼저 선점됐으므로 늦어도 이 시각 + 10초에는 선점이 풀린다. 그래서 처리할 양이 적어
     * 같은 주기 마무리 단계가 선점이 풀리기 전에 끝나도, 선점이 풀린 첫 주기에 다시 보낸다. 이 값이 없으면 다음 주기들은 보낼
     * 주문이 없어 원장이 결과를 줄 일이 없고, 마무리 단계가 돌지 않아 그 주문이 재시도 대기(약 2분)를 다 기다렸다.
     * 서버마다 따로 기억한다. 다른 서버의 증거가 없어도 그 서버는 이전처럼 재시도 대기를 따를 뿐이다.
     */
    private volatile Instant recentLedgerAnsweredFrom;

    /**
     * 복구 한 주기: 대상을 batchSize건씩 가져와 처리하고, 밀린 게 남았으면 쉬지 않고 다음 묶음을 이어서 처리합니다(#317).
     * 예전에는 한 주기에 한 묶음(50건)만 처리하고 다음 주기(30초 뒤)를 기다려, 원장이 회복된 뒤 쌓인 주문을 분당 약 100건밖에
     * 확정하지 못했습니다(50건 처리에 약 0.6초, 나머지는 쉬는 시간).
     *
     * 이렇게 되면 주기를 끝냅니다.
     * - 가져온 대상이 batchSize보다 적음: 밀린 게 없음. 단 이 주기에 원장이 결과를 준 적이 있으면(원장이 살아 있음),
     *   재시도 대기와 관계없이 원장이 처음 결과를 준 호출보다 먼저 보낸 PROCESSING을 이어서 처리한다. 장애 중 헛걸음으로 대기가
     *   2~4분 늘어난 주문이, 원장이 회복된 뒤에도 그 대기를 기다려 늦게 확정되던 꼬리를 없애려는 것이다.
     *   이번 주기에 장애 끝자락에 보냈다가 시간 초과로 남은 주문도 여기에 들어간다. 원장이 결과를 주기 시작한 뒤에 보냈는데
     *   남은 주문은 원장 장애가 아닌 다른 이유일 수 있어 같은 주기에 다시 보내지 않는다(같은 주문을 되풀이해 보내지 않게).
     *   이 주기에 원장이 결과를 주지 않았어도, 최근 주기에 원장이 처음 결과를 준 시각부터 선점 유효 시간이 지난 뒤 시작한 주기까지는
     *   그 시각을 기준으로 마무리 단계를 돈다(recentLedgerAnsweredFrom 참고)
     * - 원장 서킷이 열려 있거나 원장이 연달아 결과를 주지 못함: 아직 장애라 다음 주기에 다시 봄.
     *   서킷이 열려 있으면 주문을 고르지도 않아 확인 횟수가 오르지 않는다
     * - 한 묶음에서 확정한 주문이 없음: 오류가 이어지는 등 더 돌아도 진전이 없음
     * - timeBudget이 지남: 스케줄러 스레드(환율 수집·예약 만료와 나눠 씀)를 오래 붙잡지 않게 남은 건 다음 주기로 넘김
     * 원장에는 지금처럼 한 건씩 보냅니다. 동시에 보내면 환전 거래가 모두 같은 시스템 계정을 잠가 처리량이 늘지 않습니다
     * (원장 속도 측정: 동시 1건 초당 303건, 5건 560건, 20건 527건).
     */
    public RecoveryCycle recoverPending(int batchSize, Duration timeBudget) {
        Objects.requireNonNull(timeBudget, "timeBudget");
        Instant cycleStartedAt = clock.instant();
        Instant deadline = cycleStartedAt.plus(timeBudget);
        // 이 주기에 원장이 처음 결과를 준 호출을 보낸 시각. null이면 이 주기에는 아직 원장이 살아 있는지 모름
        Instant ledgerAnsweredFrom = null;
        // 최근 주기의 증거. 이 주기에 원장이 결과를 주지 않았을 때 마무리 단계 기준으로 쓴다
        Instant carriedAnsweredFrom = recentLedgerAnsweredFrom;
        Instant sweepFrom = null;
        boolean backoffSkipped = false;
        int batches = 0;
        int found = 0;
        int settled = 0;
        int unsettled = 0;
        int errors = 0;
        EndReason endedBy;
        while (true) {
            Batch batch = recoverBatch(batchSize, backoffSkipped ? sweepFrom : null);
            batches++;
            found += batch.result().found();
            settled += batch.result().settled();
            unsettled += batch.result().unsettled();
            errors += batch.result().errors();
            // 확정(COMPLETED·FAILED·MANUAL_REVIEW)은 원장이 결과를 줬다는 뜻이다 = 원장이 살아 있다
            if (ledgerAnsweredFrom == null) {
                ledgerAnsweredFrom = batch.firstSettledSentAt();
            }
            if (batch.ledgerUnavailable()) {
                endedBy = EndReason.LEDGER_UNAVAILABLE;
            } else if (batch.result().found() < batchSize) {
                Instant evidence = ledgerAnsweredFrom != null ? ledgerAnsweredFrom : carriedAnsweredFrom;
                if (!backoffSkipped && evidence != null && clock.instant().isBefore(deadline)) {
                    sweepFrom = evidence;
                    backoffSkipped = true;
                    continue;
                }
                endedBy = EndReason.NOTHING_LEFT;
            } else if (batch.result().settled() == 0) {
                endedBy = EndReason.NO_PROGRESS;
            } else if (!clock.instant().isBefore(deadline)) {
                endedBy = EndReason.TIME_BUDGET;
            } else {
                continue;
            }
            break;
        }
        rememberLedgerAnswer(ledgerAnsweredFrom, carriedAnsweredFrom, cycleStartedAt);
        RecoveryCycle cycle =new RecoveryCycle(batches, found, settled, unsettled, errors, endedBy, backoffSkipped);
        if (found > 0) {
            log.info("환전 주문 복구 주기: batches={}, found={}, settled={}, unsettled={}, errors={}, endedBy={}, backoffSkipped={}",
                    batches, found, settled, unsettled, errors, endedBy, backoffSkipped);
        }
        if (endedBy == EndReason.TIME_BUDGET) {
            log.warn("복구 제한 시간({})이 지나 남은 주문은 다음 주기로 넘깁니다: settled={}", timeBudget, settled);
        }
        return cycle;
    }

    /**
     * 이 주기에 원장이 결과를 줬으면 그 시각을 새 증거로 기억합니다. 주지 않았으면, 이 주기가 기억한 시각 + 선점 유효 시간 뒤에
     * 시작했을 때(그보다 먼저 선점된 주문의 선점이 모두 풀린 뒤 마무리 단계를 한 번 돈 뒤) 증거를 지웁니다.
     */
    private void rememberLedgerAnswer(Instant ledgerAnsweredFrom, Instant carriedAnsweredFrom, Instant cycleStartedAt) {
        if (ledgerAnsweredFrom != null) {
            recentLedgerAnsweredFrom = ledgerAnsweredFrom;
        } else if (carriedAnsweredFrom != null
                && !cycleStartedAt.isBefore(carriedAnsweredFrom.plus(ExchangeOrderLedgerProcessor.CLAIM_LEASE))) {
            recentLedgerAnsweredFrom = null;
        }
    }

    /** 대상 주문 한 묶음을 처리하고 결과 건수를 돌려줍니다. 한 주문의 예상 밖 오류는 다른 주문 처리를 막지 않습니다. */
    public RecoveryResult recover(int limit) {
        return recoverBatch(limit, null).result();
    }

    /**
     * notCheckedSince가 null이면 재시도 대기가 지난 주문을, 아니면 대기와 관계없이 그 시각 이후로 보내지 않은 주문을 고릅니다.
     * 이 묶음에서 처음 확정된 주문을 보내기 직전 시각도 돌려줍니다. 선점(확인 시각 기록)이 이 시각 이후라, 이보다 먼저 확인한
     * 주문은 원장이 결과를 주기 전에 보낸 주문입니다.
     */
    private Batch recoverBatch(int limit, Instant notCheckedSince) {
        // 서킷이 열려 원장에 보낼 수 없으면 주문을 고르지도 않는다. 선점하면 보내지도 못하고 확인 횟수(재시도 대기)만 올라,
        // 원장이 회복된 뒤에도 그 주문이 늦게 처리된다(#317)
        if (!ledgerClient.isAvailable()) {
            log.debug("원장 서킷이 열려 있어 이번 복구는 건너뜁니다");
            return new Batch(new RecoveryResult(0, 0, 0, 0), true, null);
        }
        List<ExchangeOrder> targets = notCheckedSince == null
                ? orderRepository.findRecoveryTargets(clock.instant(), limit)
                : orderRepository.findNotCheckedSince(notCheckedSince, limit);
        int settled = 0;
        int unsettled = 0;
        int errors = 0;
        int consecutiveUnsettled = 0;
        boolean ledgerUnavailable = false;
        Instant firstSettledSentAt = null;
        for (ExchangeOrder target : targets) {
            if (consecutiveUnsettled >= MAX_CONSECUTIVE_UNSETTLED) {
                log.warn("원장 결과를 연달아 확인하지 못해 남은 주문은 다음 주기로 미룹니다: remaining={}",
                        targets.size() - settled - unsettled - errors);
                ledgerUnavailable = true;
                break;
            }
            if (!ledgerClient.isAvailable()) {
                log.warn("처리 중 원장 서킷이 열려 남은 주문은 다음 주기로 미룹니다: remaining={}",
                        targets.size() - settled - unsettled - errors);
                ledgerUnavailable = true;
                break;
            }
            Instant sentAt = clock.instant();
            try {
                ExchangeOrder processed = ledgerProcessor.process(target);
                if (processed.getStatus() == ExchangeOrderStatus.PROCESSING) {
                    unsettled++;
                    consecutiveUnsettled++;
                } else {
                    settled++;
                    consecutiveUnsettled = 0;
                    if (firstSettledSentAt == null) {
                        firstSettledSentAt = sentAt;
                    }
                }
            } catch (RuntimeException e) {
                errors++;
                consecutiveUnsettled++;
                log.error("주문 복구 중 오류: orderId={}, exception={}", target.getId(), e.getClass().getName(), e);
            }
        }
        RecoveryResult result = new RecoveryResult(targets.size(), settled, unsettled, errors);
        if (result.found() > 0) {
            log.debug("환전 주문 복구 묶음: found={}, settled={}, unsettled={}, errors={}",
                    result.found(), result.settled(), result.unsettled(), result.errors());
        }
        return new Batch(result, ledgerUnavailable, firstSettledSentAt);
    }

    /**
     * 복구 한 묶음의 결과입니다.
     *
     * @param settled   COMPLETED·FAILED·MANUAL_REVIEW로 바뀐 주문 수
     * @param unsettled 원장 결과를 모르거나 다른 흐름이 처리 중이라 PROCESSING으로 남은 주문 수
     */
    public record RecoveryResult(int found, int settled, int unsettled, int errors) {
    }

    /**
     * 복구 한 주기(묶음 여러 개)의 합계와 주기를 끝낸 이유입니다.
     *
     * @param backoffSkipped 원장이 살아 있음을 확인한 뒤 재시도 대기와 관계없이 남은 주문을 처리했는지
     */
    public record RecoveryCycle(int batches, int found, int settled, int unsettled, int errors, EndReason endedBy,
                                boolean backoffSkipped) {
    }

    /** 복구 주기를 끝낸 이유 */
    public enum EndReason {
        /** 가져온 대상이 묶음 크기보다 적어 밀린 게 없음 */
        NOTHING_LEFT,
        /** 원장 서킷이 열려 있거나 원장이 연달아 결과를 주지 못해 아직 장애로 봄 */
        LEDGER_UNAVAILABLE,
        /** 한 묶음에서 확정한 주문이 없어 더 돌아도 진전이 없음 */
        NO_PROGRESS,
        /** 제한 시간이 지나 남은 주문을 다음 주기로 넘김 */
        TIME_BUDGET
    }

    /**
     * @param firstSettledSentAt 이 묶음에서 처음 확정된 주문을 보내기 직전 시각. 확정이 없으면 null
     */
    private record Batch(RecoveryResult result, boolean ledgerUnavailable, Instant firstSettledSentAt) {
    }
}
