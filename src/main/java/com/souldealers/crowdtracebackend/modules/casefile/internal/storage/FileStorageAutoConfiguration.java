package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FileStorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FileStorage.class)
    FileStorage fileStorage() {
        return new InMemoryFileStorage();
    }
}
