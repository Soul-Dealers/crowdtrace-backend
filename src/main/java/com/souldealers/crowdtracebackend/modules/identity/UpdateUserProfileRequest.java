package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 255, message = "Display name must not exceed 255 characters")
        String displayName) {
}
