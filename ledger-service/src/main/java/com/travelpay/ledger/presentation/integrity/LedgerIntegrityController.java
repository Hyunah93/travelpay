package com.travelpay.ledger.presentation.integrity;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.ledger.application.integrity.LedgerIntegrityService;
import com.travelpay.ledger.presentation.integrity.dto.IntegrityCheckResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/internal/v1/ledger/integrity")
@RequiredArgsConstructor
public class LedgerIntegrityController {

    private final LedgerIntegrityService integrityService;

    /**
     * 원장 내부 정합성 검사. 위반이 있어도 200(위반 목록으로 알림).
     * 다른 검사가 실행 중이면 기다리지 않고 409, 검사 timeout·DB 장애는 503.
     */
    @GetMapping
    public ApiResponse<IntegrityCheckResponse> check() {
        try {
            return ApiResponse.success(IntegrityCheckResponse.from(integrityService.check()));
        } catch (RuntimeException e) {
            // 트랜잭션 시작(커넥션 획득) 실패까지 잡히도록 서비스 프록시 바깥에서 기록. 예외 메시지는 SQL이 섞일 수 있어 남기지 않음
            log.warn("원장 정합성 검사 실패 exception={}", e.getClass().getName());
            throw e;
        }
    }
}
