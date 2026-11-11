package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicatePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsConfiguredThresholds() {
        runner.withPropertyValues("crowdtrace.duplicates.date-window-days=14",
                "crowdtrace.duplicates.name-threshold-permille=950").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DuplicateProperties.class).getDateWindowDays()).isEqualTo(14);
            assertThat(context.getBean(DuplicateProperties.class).getNameThresholdPermille()).isEqualTo(950);
        });
    }

    @Test
    void invalidConfigurationFailsStartup() {
        for (String setting : new String[]{"date-window-days=-1", "date-window-days=32768",
                "name-threshold-permille=-1", "name-threshold-permille=1001"}) {
            runner.withPropertyValues("crowdtrace.duplicates." + setting)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DuplicateProperties.class)
    static class PropertiesConfiguration {}
}
