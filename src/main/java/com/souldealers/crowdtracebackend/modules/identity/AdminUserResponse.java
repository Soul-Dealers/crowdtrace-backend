package com.souldealers.crowdtracebackend.modules.identity;

import java.time.LocalDateTime;

/** One row of the Super Admin user list. */
public record AdminUserResponse(Long id, String displayName, String email, UserRoles role, UserStatus status,
                                LocalDateTime createdAt) {
}
