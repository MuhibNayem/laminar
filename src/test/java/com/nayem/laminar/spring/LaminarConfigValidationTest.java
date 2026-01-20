package com.nayem.laminar.spring;

import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LaminarConfigValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LaminarAutoConfiguration.class));

    @Test
    void shouldFailOnNegativeMaxWaiters() {
        contextRunner.withPropertyValues("laminar.max-waiters=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void shouldFailOnNegativeCachedWorkers() {
        contextRunner.withPropertyValues("laminar.max-cached-workers=-5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void shouldFailOnInvalidShards() {
        contextRunner.withPropertyValues("laminar.cluster.enabled=true", "laminar.cluster.shards=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }
}
