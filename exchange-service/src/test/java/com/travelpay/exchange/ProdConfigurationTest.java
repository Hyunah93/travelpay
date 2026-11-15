package com.travelpay.exchange;

import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 배포는 SPRING_CONFIG_NAME=application-prod로 application-prod.yml만 읽습니다(application.yml을 읽지 않음).
 * 그래서 동작 전제가 되는 설정이 prod 파일에서 빠지면 기본값으로 바뀌어도 알아채기 어렵습니다. 두 파일의 값이 같은지 확인합니다.
 */
class ProdConfigurationTest {

    private static final Properties LOCAL = load("application.yml");
    private static final Properties PROD = load("application-prod.yml");

    /**
     * 스케줄러 스레드 수: 1이면 주문 복구가 길어질 때 환율 수집이 멈춥니다.
     * 원장 타임아웃: 빠지면 OpenFeign 기본값(연결 10초, 읽기 60초)이 되어 선점 유효 기간(10초)의 전제가 깨집니다.
     * 캐시 Redis 타임아웃: 빠지면 Lettuce 기본값(60초)이 되어 Redis가 멈췄을 때 조회가 DB로 넘어가지 못하고 붙잡힙니다.
     * Redis health: 켜지면 캐시 Redis가 꺼졌을 때 DB로 동작 중인 앱까지 DOWN이 됩니다.
     * 주문 복구(#317): 주기·제한 시간·적체 지표 간격이 원장 회복 뒤 확정 속도와 스케줄러 스레드 사용을 정합니다.
     * 원장 서킷(#282): 열고 닫는 기준이 원장 장애가 다른 기능으로 번지는지를 정합니다.
     * 예약 환전(#240): 기간·대기 수 제한과 만료 주기가 예약 규칙과 만료 처리 속도를 정합니다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "exchange.order.recovery.enabled",
            "exchange.order.recovery.fixed-delay",
            "exchange.order.recovery.batch-size",
            "exchange.order.recovery.time-budget",
            "exchange.order.recovery.backlog-refresh",
            "exchange.ledger.circuit-breaker.sliding-window",
            "exchange.ledger.circuit-breaker.minimum-calls",
            "exchange.ledger.circuit-breaker.failure-rate-percent",
            "exchange.ledger.circuit-breaker.slow-call-duration",
            "exchange.ledger.circuit-breaker.slow-call-rate-percent",
            "exchange.ledger.circuit-breaker.wait-in-open",
            "exchange.ledger.circuit-breaker.half-open-calls",
            "exchange.reservation.max-period",
            "exchange.reservation.max-waiting-per-user",
            "exchange.reservation.expiry.enabled",
            "exchange.reservation.expiry.fixed-delay",
            "exchange.reservation.expiry.batch-size",
            "spring.task.scheduling.pool.size",
            "spring.cloud.openfeign.client.config.ledger-service.connect-timeout",
            "spring.cloud.openfeign.client.config.ledger-service.read-timeout",
            "spring.data.redis.connect-timeout",
            "spring.data.redis.timeout",
            "spring.data.redis.repositories.enabled",
            "management.health.redis.enabled",
            "exchange.rate.cache.enabled",
    })
    void prodKeepsSameValueAsLocal(String key) {
        String local = LOCAL.getProperty(key);

        assertNotNull(local, "application.yml에 없음: " + key);
        assertEquals(local, PROD.getProperty(key), "application-prod.yml 값이 다르거나 없음: " + key);
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        return yaml.getObject();
    }
}
