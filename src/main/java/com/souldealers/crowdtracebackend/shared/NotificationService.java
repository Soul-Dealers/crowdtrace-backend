package com.souldealers.crowdtracebackend.shared;


/**
 * Service interface for sending notifications (email, SMS, push).
 * Implementations can use different providers (Resend, SendGrid, Twilio, etc.)
 */
public interface NotificationService {

    /**
     * Sends an OTP verification email to the specified recipient.
     *
     * @param to        recipient email address
     * @param otpCode   the OTP code to include in the email
     * @param userName  the user's display name for personalization
     * @param type      the purpose of the OTP (account creation or password reset)
     */
    void sendOtpEmail(String to, String otpCode, String userName, OtpType type);

    /**
     * Sends a welcome email after successful account verification.
     *
     * @param to       recipient email address
     * @param userName the user's display name
     */
    void sendWelcomeEmail(String to, String userName);

}
