package com.souldealers.crowdtracebackend.modules.casefile.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsentConfiguration.class);

    @Test
    void rejectsAnUnconfiguredConsentVersionAtStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "123456789012345678901234567890123"})
    void rejectsInvalidConsentVersionsAtStartup(String version) {
        runner.withPropertyValues("crowdtrace.consent.sensitive-data-version=" + version)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("sensitiveDataVersion");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "12345678901234567890123456789012"})
    void bindsAValidConsentVersion(String version) {
        runner.withPropertyValues("crowdtrace.consent.sensitive-data-version=" + version)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConsentProperties.class).getSensitiveDataVersion()).isEqualTo(version);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConsentProperties.class)
    static class ConsentConfiguration {
    }
}
