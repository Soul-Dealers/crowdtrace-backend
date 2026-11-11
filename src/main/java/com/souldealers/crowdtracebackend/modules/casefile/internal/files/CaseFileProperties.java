package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crowdtrace.case-files")
public class CaseFileProperties {
    private long maxSizeBytes = 10L * 1024 * 1024;
    private int maxPhotosPerCase = 5;
}
