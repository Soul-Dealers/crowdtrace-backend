package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class S3StorageConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfiguration.class)
            .withConfiguration(AutoConfigurations.of(FileStorageAutoConfiguration.class));

    @Test
    void usesS3AndDropsTheInMemoryFallbackWhenABucketIsConfigured() {
        runner.withPropertyValues("crowdtrace.storage.s3.bucket=case-files", "crowdtrace.storage.s3.region=eu-west-1",
                "crowdtrace.storage.s3.endpoint=http://localhost:9000", "crowdtrace.storage.s3.path-style-access=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(FileStorage.class).doesNotHaveBean(InMemoryFileStorage.class);
                    assertThat(context.getBean(FileStorage.class)).isInstanceOf(S3FileStorage.class);
                    assertThat(context).hasSingleBean(SignedFileAccess.class);
                });
    }

    @Test
    void keepsInMemoryStorageWithoutSignedAccessWhenNoBucketIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InMemoryFileStorage.class);
            assertThat(context).doesNotHaveBean(SignedFileAccess.class);
        });
    }

    @Test
    void failsFastOnABlankBucket() {
        runner.withPropertyValues("crowdtrace.storage.s3.bucket= ", "crowdtrace.storage.s3.region=eu-west-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultsTheMaximumDownloadLifetimeToFifteenMinutes() {
        runner.withPropertyValues("crowdtrace.storage.s3.bucket=case-files", "crowdtrace.storage.s3.region=eu-west-1")
                .run(context -> assertThat(context.getBean(S3StorageProperties.class).maxDownloadTtl())
                        .hasMinutes(15));
    }
}
