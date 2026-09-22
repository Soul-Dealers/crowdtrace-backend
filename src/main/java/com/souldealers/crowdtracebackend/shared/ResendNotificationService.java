package com.souldealers.crowdtracebackend.shared;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;
@Service
@RequiredArgsConstructor
@Slf4j
public class ResendNotificationService implements NotificationService {
    private final ITemplateEngine templateEngine;

    @Value("${resend.url}")
    private String resendUrl;

    @Value("${resend.api-key}")
    private String resendApiKey;

    @Value("${resend.from-email}")
    private String fromEmail;


    @Override
    @Async
    public void sendOtpEmail(String to, String otpCode, String userName, OtpType type) {
        String subject = otpEmailSubject(type);
        String htmlContent = renderOtpEmail(otpCode, userName, type);

        sendEmail(to, subject, htmlContent);
        log.info("OTP email sent to: {}", to);
    }

    @Override
    @Async
    public void sendWelcomeEmail(String to, String userName) {
        String subject = "Welcome to CrowdTrace!";
        String htmlContent = renderWelcomeEmail(userName);

        sendEmail(to, subject, htmlContent);
        log.info("Welcome email sent to: {}", to);
    }

    private void sendEmail(String toEmail, String subject, String htmlContent) {
        try {
            RestClient restClient = RestClient.create();

            Map<String, Object> requestBody = Map.of(
                    "from", fromEmail,
                    "to", toEmail,
                    "subject", subject,
                    "html", htmlContent
            );

            restClient.post()
                    .uri(resendUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Email sent successfully to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
            // Don't throw - email sending should not block the main flow
        }
    }

    String otpEmailSubject(OtpType type) {
        return switch (type) {
            case CREATE -> "Verify your CrowdTrace account";
            case RESET -> "Reset your CrowdTrace password";
        };
    }

    String otpEmailTemplate(OtpType type) {
        return switch (type) {
            case CREATE -> "otp-verification";
            case RESET -> "password-reset-otp";
        };
    }

    String renderOtpEmail(String otpCode, String userName, OtpType type) {
        Context context = new Context();
        context.setVariable("otpCode", otpCode);
        context.setVariable("userName", userName);
        return templateEngine.process(otpEmailTemplate(type), context);
    }

    String renderWelcomeEmail(String userName) {
        Context context = new Context();
        context.setVariable("userName", userName);
        return templateEngine.process("welcome-email", context);
    }

}
