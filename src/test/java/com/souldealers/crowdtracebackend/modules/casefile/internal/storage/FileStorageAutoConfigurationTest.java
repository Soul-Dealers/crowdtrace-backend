package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileStorageAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileStorageAutoConfiguration.class));

    @Test
    void registersTheFallbackWhenNoStorageProviderExists() {
        runner.run(context -> assertThat(context).hasSingleBean(FileStorage.class)
                .hasSingleBean(InMemoryFileStorage.class));
    }

    @Test
    void letsAnApplicationProviderReplaceTheFallback() {
        runner.withUserConfiguration(StorageProvider.class).run(context -> {
            assertThat(context).hasSingleBean(FileStorage.class).doesNotHaveBean(InMemoryFileStorage.class);
            assertThat(context.getBean(FileStorage.class)).isSameAs(context.getBean("realFileStorage"));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageProvider {
        @Bean
        FileStorage realFileStorage() {
            return mock(FileStorage.class);
        }
    }
}
