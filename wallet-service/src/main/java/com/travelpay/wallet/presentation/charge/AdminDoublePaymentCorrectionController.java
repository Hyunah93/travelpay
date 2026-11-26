package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.correction.DoublePaymentCorrectionQueryService;
import com.travelpay.wallet.application.correction.dto.DoublePaymentCorrectionItem;
import com.travelpay.wallet.domain.correction.ChargeReversalCorrectionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자(MASTER) 이중 지급 자동 정정 조회. Gateway의 /api/v1/admin/charges/** 라우트 아래에 둔다.
 * 정정은 스케줄러만 하며 이 API는 아무것도 바꾸지 않는다
 */
@RestController
@RequestMapping("/api/v1/admin/charges/double-payment-corrections")
@RequiredArgsConstructor
public class AdminDoublePaymentCorrectionController {

    private final DoublePaymentCorrectionQueryService queryService;

    /** status 생략 시 전체. created_at·id 내림차순 */
    @GetMapping
    public ApiResponse<PageResponse<DoublePaymentCorrectionItem>> list(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) ChargeReversalCorrectionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        return ApiResponse.success(PageResponse.from(queryService.list(status, page, size)));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
