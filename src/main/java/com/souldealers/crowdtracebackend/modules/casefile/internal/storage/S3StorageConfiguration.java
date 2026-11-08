package com.souldealers.crowdtracebackend.modules.casefile.internal.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Activates S3 storage when a bucket is configured. User configuration is processed before
 * {@link FileStorageAutoConfiguration}, so the in-memory fallback then backs off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "crowdtrace.storage.s3", name = "bucket")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfiguration {

    @Bean
    S3Client caseFileS3Client(S3StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder().serviceConfiguration(serviceConfiguration(properties));
        if (properties.region() != null) {
            builder.region(Region.of(properties.region()));
        }
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    /** Signs with the same endpoint, region and addressing as the client so URLs match stored objects. */
    @Bean
    S3Presigner caseFileS3Presigner(S3StorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder().serviceConfiguration(serviceConfiguration(properties));
        if (properties.region() != null) {
            builder.region(Region.of(properties.region()));
        }
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    @Bean
    S3FileStorage s3FileStorage(S3Client caseFileS3Client, S3Presigner caseFileS3Presigner,
                                S3StorageProperties properties) {
        return new S3FileStorage(caseFileS3Client, caseFileS3Presigner, properties.bucket(),
                properties.maxDownloadTtl());
    }

    private static S3Configuration serviceConfiguration(S3StorageProperties properties) {
        return S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build();
    }
}
