package com.travelpay.user.application.service;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 다른 연결이 표에 ACCESS EXCLUSIVE 잠금(마이그레이션 잠금 재현)을 잡은 동안, 읽기가 실행 상한에 503으로 끝나고 SQL·트랜잭션도 끝나는지 확인합니다. */
final class ReadLimitCheck {

    private ReadLimitCheck() {
    }

    static void assertGivesUpAtTheReadLimit(DataSource dataSource, String table, Executable read) throws Exception {
        try (var lock = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            long lockHolder;
            try (var statement = lock.createStatement()) {
                statement.execute("lock table " + table + " in access exclusive mode");
                try (var result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next();
                    lockHolder = result.getLong(1);
                }
            }
            try {
                var started = System.nanoTime();
                // 상한이 없으면 잠금이 풀릴 때까지 기다리므로, 시험이 멈추지 않게 넉넉히 기다린 뒤 실패로 봄
                var failure = executor.submit(() -> assertThrows(BusinessException.class, read))
                        .get(10, TimeUnit.SECONDS);
                var elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
                // Hibernate가 남은 시간을 초 단위로 바꿔 걸어 키로 찾는 조회는 1초, 질의는 2초에 끊길 수 있음
                assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) >= 0
                        && elapsed.compareTo(Duration.ofSeconds(ReadTransactions.TIMEOUT_SECONDS + 1)) < 0,
                        elapsed.toString());
                // 잠금을 잡은 채로 확인: 잠금 주인 말고는 실행 중이거나 트랜잭션을 연 세션이 없어야 SQL·트랜잭션이 끝난 것
                assertEquals(0L, otherBusySessions(dataSource, lockHolder));
            }
            finally {
                lock.rollback();
            }
        }
    }

    private static long otherBusySessions(DataSource dataSource, long lockHolder) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                select count(*) from pg_stat_activity
                where datname = current_database() and pid not in (pg_backend_pid(), ?) and state <> 'idle'
                """)) {
            statement.setLong(1, lockHolder);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
