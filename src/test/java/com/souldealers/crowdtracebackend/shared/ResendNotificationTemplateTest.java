package com.souldealers.crowdtracebackend.shared;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.ITemplateEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResendNotificationTemplateTest {

    @Test
    void mapsOtpTypeToPurposeSpecificSubjectAndTemplate() {
        ResendNotificationService service = new ResendNotificationService(mock(ITemplateEngine.class));

        assertThat(service.otpEmailSubject(OtpType.CREATE))
                .isEqualTo("Verify your CrowdTrace account");
        assertThat(service.otpEmailTemplate(OtpType.CREATE))
                .isEqualTo("otp-verification");
        assertThat(service.otpEmailSubject(OtpType.RESET))
                .isEqualTo("Reset your CrowdTrace password");
        assertThat(service.otpEmailTemplate(OtpType.RESET))
                .isEqualTo("password-reset-otp");
    }

    @Test
    void keepsOtpEmailMarkupInAClasspathTemplate() throws IOException {
        ClassPathResource template = new ClassPathResource("templates/otp-verification.html");

        assertThat(template.exists()).isTrue();
        assertThat(template.getContentAsString(StandardCharsets.UTF_8))
                .contains("Verify your email")
                .contains("th:text=\"${userName}\"")
                .contains("th:text=\"${otpCode}\"");
    }

    @Test
    void keepsPasswordResetOtpEmailMarkupInAClasspathTemplate() throws IOException {
        ClassPathResource template = new ClassPathResource("templates/password-reset-otp.html");

        assertThat(template.exists()).isTrue();
        assertThat(template.getContentAsString(StandardCharsets.UTF_8))
                .contains("<title>Reset your CrowdTrace password</title>")
                .contains("<h1")
                .contains("Reset your password")
                .contains("use this code to reset your CrowdTrace password")
                .contains("th:text=\"${userName}\"")
                .contains("th:text=\"${otpCode}\"");
    }

    @Test
    void keepsWelcomeEmailMarkupInAClasspathTemplate() throws IOException {
        ClassPathResource template = new ClassPathResource("templates/welcome-email.html");

        assertThat(template.exists()).isTrue();
        assertThat(template.getContentAsString(StandardCharsets.UTF_8))
                .contains("th:text=\"${userName}\"");
    }
}
