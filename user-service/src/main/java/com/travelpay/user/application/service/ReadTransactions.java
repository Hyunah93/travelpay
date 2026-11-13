package com.travelpay.user.application.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증·로그인·가입·마이페이지·약관·실명 조회가 함께 쓰는 읽기 전용 트랜잭션입니다. DB가 멈춰도 연결을 오래 붙잡지 않게 실행 상한을 둡니다. */
final class ReadTransactions {

    // 연결을 얻은 뒤부터 2초 기한. Hibernate가 남은 시간을 초 단위로 바꿔 문장마다 걸므로 키로 찾는 조회는 1~2초, 질의는 약 2초에 끊김.
    // 정상 조회는 수 ms. 연결을 바로 얻었다면 잠금에 막힌 조회도 세션 확인 API 응답 대기(3초) 안에 끝나 User가 직접 503을 줌
    static final int TIMEOUT_SECONDS = 2;

    private ReadTransactions() {
    }

    static TransactionTemplate create(PlatformTransactionManager transactionManager) {
        var template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setTimeout(TIMEOUT_SECONDS);
        return template;
    }
}
