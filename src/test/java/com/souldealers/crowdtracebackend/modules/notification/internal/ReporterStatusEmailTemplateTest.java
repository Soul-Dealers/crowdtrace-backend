package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class ReporterStatusEmailTemplateTest {

    private final ReporterStatusEmailComposer composer =
            new ReporterStatusEmailComposer(realEngine(), "https://crowdtrace.example");

    @Test
    void theEmailCarriesTheCaseNumberNewStatusAndLinkOnly() {
        ReporterStatusEmailComposer.Email email = composer.compose(1000043L, NotifiedCaseStatus.FOUND_DECEASED);

        assertThat(email.subject()).isEqualTo("Your CrowdTrace case 1000043 was updated");
        assertThat(email.html())
                .contains("Case 1000043")
                .contains("Found deceased")
                .contains("href=\"https://crowdtrace.example/cases/1000043\"")
                .doesNotContainIgnoringCase("moderator")
                .doesNotContainIgnoringCase("${");
    }

    @Test
    void everyStatusHasAReadableLabel() {
        for (NotifiedCaseStatus status : NotifiedCaseStatus.values()) {
            assertThat(composer.compose(1L, status).html()).doesNotContain(status.name());
        }
    }

    private static SpringTemplateEngine realEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
