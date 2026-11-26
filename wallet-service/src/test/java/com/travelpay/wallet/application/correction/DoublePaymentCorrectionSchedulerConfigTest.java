package com.travelpay.wallet.application.correction;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 이중 지급 정정 스케줄러 설정 검증. DB 없이 스케줄러 빈만 올린다 */
class DoublePaymentCorrectionSchedulerConfigTest {

    private static final String PREFIX = "travelpay.wallet.charge.correction.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DoublePaymentCorrectionSteps.class, () -> mock(DoublePaymentCorrectionSteps.class))
            .withBean(DoublePaymentCorrectionProcessor.class, () -> mock(DoublePaymentCorrectionProcessor.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(DoublePaymentCorrectionScheduler.class);

    @Test
    void 설정이_없으면_기본값으로_켜진다() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(DoublePaymentCorrectionScheduler.class));
    }

    @Test
    void enabled가_false면_등록되지_않는다() {
        contextRunner.withPropertyValues(PREFIX + "enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DoublePaymentCorrectionScheduler.class));
    }

    @Test
    void batch_size가_0이면_기동에_실패한다() {
        assertStartupFails(PREFIX + "batch-size=0", PREFIX + "batch-size", ": 0");
    }

    @Test
    void fixed_delay가_500ms면_기동에_실패한다() {
        assertStartupFails(PREFIX + "fixed-delay=500ms", PREFIX + "fixed-delay", ": 500ms");
    }

    @Test
    void fixed_delay가_1m30s면_기동된다() {
        contextRunner.withPropertyValues(PREFIX + "fixed-delay=1m30s")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(DoublePaymentCorrectionScheduler.class));
    }

    private void assertStartupFails(String property, String key, String value) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining(value);
        });
    }
}
