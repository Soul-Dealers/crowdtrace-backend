package com.souldealers.crowdtracebackend.modules.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request object for resending OTP")
public record ResendOtpRequest(
        @NotBlank
        @Email
        String email,

        @Schema(description = "Type of OTP request. Can be either 'create' or 'reset'",
                allowableValues = {"create", "reset"}
        )
        String type
) {
    public ResendOtpRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
