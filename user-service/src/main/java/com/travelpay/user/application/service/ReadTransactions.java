package com.travelpay.user.application.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증·로그인·가입·마이페이지·약관·실명 조회가 함께 쓰는 읽기 전용 트랜잭션입니다. 잠금 등으로 조회가 막혀도 연결을 오래 붙잡지 않게 실행 상한을 둡니다(DB 서버 자체가 멈춘 경우는 연결 풀의 소켓 읽기 상한이 맡음). */
final class ReadTransactions {

    // 연결을 얻은 뒤의 2초 기한을 JDBC에 전달. 바로 시작한 단일 조회의 잠금 대기는 약 1~2초에 끝남.
    // 남은 시간이 0.5초 미만인 질의는 Hibernate가 제한을 0(없음)으로 반올림하므로, 개별 잠금에는 앱 풀의 lock_timeout(2초)도 적용.
    // 선행 SQL·연결 획득 등이 겹치면 I01 응답 대기(3초)를 넘을 수 있으며, 전체 HTTP 요청의 2초 상한은 아님.
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
