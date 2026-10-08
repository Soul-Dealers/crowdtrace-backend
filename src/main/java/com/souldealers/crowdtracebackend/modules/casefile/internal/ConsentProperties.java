package com.souldealers.crowdtracebackend.modules.casefile.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crowdtrace.consent")
@Validated
public class ConsentProperties {

    @NotBlank
    @Size(max = 32)
    private String sensitiveDataVersion;
}
