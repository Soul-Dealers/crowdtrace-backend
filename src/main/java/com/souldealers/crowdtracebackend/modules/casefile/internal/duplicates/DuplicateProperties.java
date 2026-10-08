package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crowdtrace.duplicates")
@Validated
public class DuplicateProperties {
    @Min(0)
    @Max(32767)
    private int dateWindowDays = 7;

    @Min(0)
    @Max(1000)
    private int nameThresholdPermille = 900;
}
