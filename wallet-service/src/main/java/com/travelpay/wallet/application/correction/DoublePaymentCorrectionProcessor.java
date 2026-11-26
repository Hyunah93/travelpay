package com.travelpay.wallet.application.correction;

import com.travelpay.wallet.application.correction.DoublePaymentCorrectionJudge.AlreadyReversed;
import com.travelpay.wallet.application.correction.DoublePaymentCorrectionJudge.Decision;
import com.travelpay.wallet.application.correction.DoublePaymentCorrectionJudge.NeedsReview;
import com.travelpay.wallet.application.correction.DoublePaymentCorrectionJudge.Post;
import com.travelpay.wallet.application.correction.DoublePaymentCorrectionJudge.Unavailable;
import com.travelpay.wallet.application.correction.dto.ChargeReversalResult;
import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 선점한 정정 1건 처리. 대사 결과·운영자 입력을 믿지 않고 실행 직전 은행 환불·원장 적립·원장 취소 분개를 단건 재조회한 사실로
 * 판단하며(DoublePaymentCorrectionJudge), 충전 상태와 대사 결과(reconciliation_results)는 바꾸지 않는다.
 * 외부 호출은 트랜잭션 밖에서 하고, 결과는 DoublePaymentCorrectionSteps의 짧은 트랜잭션으로 기록한다.
 * <p>
 * 선점 토큰: 결과 기록 UPDATE는 status = PENDING and updated_at = 선점 때 받은 값일 때만 반영된다.
 * 토큰은 처리 소유권을 구분하는 값이다. 임대(30초)가 끝나 다른 처리기가 다시 선점하면 updated_at이 바뀌어 이전 처리기는
 * 기록하지 못한다 (덮어쓰지 않고 INFO 로그만). 두 처리기가 모두 원장에 보내도 분개는 원장 멱등
 * (CHARGE_REVERSAL, chargeId 하나만 저장, 재요청은 200·같은 거래)이 한 번만 남긴다.
 * <p>
 * 결과 모름(Unknown)은 attempt_count + 1, 30초 × 2^(n−1) 뒤 재시도, 5번째면 NEEDS_REVIEW(RETRY_EXHAUSTED).
 * 근거는 payment-transfer.md D5 팀 복구 정책(결과 모름 5회, 30초부터 두 배)으로 충전·송금 복구와 같은 값이다.
 * 재조회 Unavailable은 원장에 보내지 않았으므로 횟수를 세지 않고(충전 복구 NotSent와 같은 이유), 선점이 미룬
 * next_attempt_at(30초 뒤)에 다시 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoublePaymentCorrectionProcessor {

    /** 결과 모름이 이 횟수에 이르면 NEEDS_REVIEW(RETRY_EXHAUSTED). D5 팀 복구 정책 */
    static final int MAX_UNKNOWN_ATTEMPTS = 5;
    /** 결과 모름 첫 재시도 대기. 이후 두 배씩 (30·60·120·240초). D5 팀 복구 정책 */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);
    static final String REVERSAL_DESCRIPTION = "이중 지급 자동 정정";

    private final DoublePaymentCorrectionSteps steps;
    private final BankTransactionQueryClient bankClient;
    private final LedgerTransactionQueryClient ledgerClient;
    private final LedgerChargeReversalClient reversalClient;

    public enum Outcome {
        SUCCEEDED,
        NEEDS_REVIEW,
        /** 원장 결과 모름, 재시도 대기 */
        RETRY_SCHEDULED,
        /** 재조회 실패, 기록 없음 */
        UNAVAILABLE,
        /** 다른 처리기가 다시 선점해 기록하지 못함 */
        NOT_RECORDED
    }

    /** 결과 모름 n번째(1부터)의 다음 시도 대기: 30초 × 2^(n−1) */
    static Duration retryDelay(int attempt) {
        return FIRST_RETRY_DELAY.multipliedBy(1L << (attempt - 1));
    }

    /** token: 선점 때 받은 updated_at */
    public Outcome process(ChargeReversalCorrectionTarget target, Instant token) {
        Optional<ChargeRequest> loaded = steps.findCharge(target.chargeId());
        if (loaded.isEmpty() || !DoublePaymentCorrectionJudge.isCorrectable(loaded.get().getStatus())) {
            String status = loaded.map(c -> c.getStatus().name()).orElse("없음");
            return needsReview(target, token, target.attemptCount(),
                    CorrectionErrorCode.CHARGE_STATUS, "충전 상태 " + status);
        }
        ChargeRequest charge = loaded.get();
        SourceLookup<BankTransactionRecord> refund =
                query(() -> bankClient.findTransaction(charge.getRefundRequestId()));
        SourceLookup<LedgerTransactionRecord> ledger = query(() -> ledgerClient.findChargeTransaction(charge.getId()));
        SourceLookup<LedgerTransactionRecord> reversal = query(() -> ledgerClient.findChargeReversal(charge.getId()));

        Decision decision = DoublePaymentCorrectionJudge.decide(charge, refund, ledger, reversal);
        return switch (decision) {
            case Unavailable unavailable -> {
                log.info("이중 지급 정정 재조회 실패로 다음 주기에 다시 시도 correctionId={} chargeId={} sources={}",
                        target.id(), target.chargeId(), unavailable.reason());
                yield Outcome.UNAVAILABLE;
            }
            case AlreadyReversed done -> succeeded(target, token, done.ledgerTxId(), done.recoveredAmount(),
                    done.receivableAmount());
            case NeedsReview review -> needsReview(target, token, target.attemptCount(), review.code(),
                    review.detail());
            case Post ignored -> post(target, token, charge);
        };
    }

    private Outcome post(ChargeReversalCorrectionTarget target, Instant token, ChargeRequest charge) {
        ChargeReversalResult result = reversalClient.reverse(charge.getId(), charge.getAmount(), REVERSAL_DESCRIPTION);
        int attempts = target.attemptCount();
        return switch (result) {
            case ChargeReversalResult.Reversed reversed -> succeeded(target, token, reversed.transactionId(),
                    reversed.recoveredAmount(), reversed.receivableAmount());
            case ChargeReversalResult.NotReversible ignored -> needsReview(target, token, attempts,
                    CorrectionErrorCode.NOT_REVERSIBLE, "원장 422 LEDGER_ORIGINAL_NOT_REVERSIBLE");
            case ChargeReversalResult.Suspended ignored -> needsReview(target, token, attempts,
                    CorrectionErrorCode.WALLET_SUSPENDED, "원장 422 LEDGER_REJECTED_WALLET_SUSPENDED");
            case ChargeReversalResult.OriginalNotFound ignored -> needsReview(target, token, attempts,
                    CorrectionErrorCode.ORIGINAL_NOT_FOUND, "원장 404 LEDGER_TRANSACTION_NOT_FOUND");
            case ChargeReversalResult.Invalid invalid -> needsReview(target, token, attempts,
                    CorrectionErrorCode.INVALID_RESPONSE, invalid.cause());
            case ChargeReversalResult.Unknown unknown -> unknown(target, token, unknown.errorClass());
        };
    }

    private Outcome unknown(ChargeReversalCorrectionTarget target, Instant token, String errorClass) {
        int attempt = target.attemptCount() + 1;
        if (attempt >= MAX_UNKNOWN_ATTEMPTS) {
            return needsReview(target, token, attempt, CorrectionErrorCode.RETRY_EXHAUSTED,
                    "원장 결과 모름 " + attempt + "회 (" + errorClass + ")");
        }
        Duration delay = retryDelay(attempt);
        if (!steps.recordRetry(target.id(), token, attempt, delay,
                CorrectionErrorCode.LEDGER_UNKNOWN.withDetail(errorClass))) {
            return notRecorded(target);
        }
        log.warn("이중 지급 정정 원장 결과 모름 correctionId={} chargeId={} attempt={} retryAfter={} exception={}",
                target.id(), target.chargeId(), attempt, delay, errorClass);
        return Outcome.RETRY_SCHEDULED;
    }

    private Outcome succeeded(ChargeReversalCorrectionTarget target, Instant token, UUID ledgerTxId,
                              long recovered, long receivable) {
        if (!steps.recordSucceeded(target.id(), token, ledgerTxId, recovered, receivable)) {
            return notRecorded(target);
        }
        log.info("이중 지급 정정 완료 correctionId={} chargeId={} ledgerTxId={} recovered={} receivable={}",
                target.id(), target.chargeId(), ledgerTxId, recovered, receivable);
        return Outcome.SUCCEEDED;
    }

    private Outcome needsReview(ChargeReversalCorrectionTarget target, Instant token, int attemptCount,
                                CorrectionErrorCode code, String detail) {
        if (!steps.recordNeedsReview(target.id(), token, attemptCount, code.withDetail(detail))) {
            return notRecorded(target);
        }
        log.warn("이중 지급 정정 사람 확인 필요 correctionId={} chargeId={} code={}", target.id(), target.chargeId(),
                code);
        return Outcome.NEEDS_REVIEW;
    }

    private static Outcome notRecorded(ChargeReversalCorrectionTarget target) {
        log.info("이중 지급 정정 결과를 기록하지 않음: 다른 처리기가 다시 선점함 correctionId={} chargeId={}",
                target.id(), target.chargeId());
        return Outcome.NOT_RECORDED;
    }

    /** 포트 계약상 실패는 Unavailable로 오지만, 예상 밖 예외도 조회 불가로 본다 (ChargeResolutionService.query와 같음) */
    private static <T> SourceLookup<T> query(Supplier<SourceLookup<T>> call) {
        try {
            SourceLookup<T> result = call.get();
            return result != null ? result : SourceLookup.unavailable("null response");
        } catch (RuntimeException e) {
            log.warn("이중 지급 정정 단건 조회 실패 exception={}", e.getClass().getName());
            return SourceLookup.unavailable(e.getClass().getName());
        }
    }
}
