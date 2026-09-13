package com.souldealers.crowdtracebackend.modules.identity;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record UserResponse(
        String displayName,
        String email,
        UserRoles role,
        UserStatus accountStatus,
        LocalDateTime createdAt) {
}
