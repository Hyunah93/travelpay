package com.travelpay.wallet.application.correction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.correction.dto.DoublePaymentCorrectionItem;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionRepository;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 이중 지급 정정 조회. 페이지 규칙은 관리자 대사 조회(ReconciliationQueryService)와 같다:
 * page는 0부터, size는 1 이상이며 최대 크기를 넘으면 최대 크기로 조회, offset이 int 범위를 넘으면 400.
 * 정렬은 기존 목록처럼 생성 시각·id 내림차순 (최근 등록순)
 */
@Service
@RequiredArgsConstructor
public class DoublePaymentCorrectionQueryService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final Sort SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ChargeReversalCorrectionRepository repository;

    /** status가 null이면 전체 */
    @Transactional(readOnly = true, timeout = DoublePaymentCorrectionSteps.TX_TIMEOUT_SECONDS)
    public Page<DoublePaymentCorrectionItem> list(ChargeReversalCorrectionStatus status, int page, int size) {
        return repository.findAll(status, pageable(page, size)).map(DoublePaymentCorrectionItem::from);
    }

    private static Pageable pageable(int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), SORT);
        // offset이 int를 넘으면 JPA firstResult(int)에서 넘친다
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return pageable;
    }
}
