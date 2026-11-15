package com.travelpay.exchange.presentation.order;

import java.time.Duration;

import com.travelpay.exchange.application.order.ExchangeOrderBacklogMonitor;
import com.travelpay.exchange.application.order.ExchangeOrderRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderRecoverySchedulerTest {

    private static final Duration BUDGET = Duration.ofSeconds(20);

    private final ExchangeOrderRecoveryService recoveryService = mock(ExchangeOrderRecoveryService.class);
    private final ExchangeOrderBacklogMonitor backlogMonitor = mock(ExchangeOrderBacklogMonitor.class);

    @Test
    void recoversPendingWithConfiguredBatchSizeAndTimeBudget() {
        new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 20, BUDGET).recover();

        verify(recoveryService).recoverPending(20, BUDGET);
    }

    /** 복구는 자주(5초) 돌고 적체 지표는 따로(30초) 센다. 복구 주기에서는 지표를 세지 않는다(#317) */
    @Test
    void recoveryDoesNotRefreshBacklog() {
        new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 20, BUDGET).recover();

        verify(backlogMonitor, never()).refresh();
    }

    @Test
    void refreshesBacklogOnItsOwnSchedule() {
        new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 20, BUDGET).refreshBacklog();

        verify(backlogMonitor).refresh();
        verify(recoveryService, never()).recoverPending(anyInt(), any());
    }

    @Test
    void failedCycleDoesNotStopScheduling() {
        when(recoveryService.recoverPending(50, BUDGET)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 50, BUDGET).recover());
    }

    @Test
    void rejectsBatchSizeBelowOne() {
        assertThrows(IllegalStateException.class,
                () -> new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 0, BUDGET));
    }

    @Test
    void rejectsTimeBudgetNotAboveZero() {
        assertThrows(IllegalStateException.class,
                () -> new ExchangeOrderRecoveryScheduler(recoveryService, backlogMonitor, 50, Duration.ZERO));
    }
}
