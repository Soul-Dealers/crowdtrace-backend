package com.souldealers.crowdtracebackend.modules.identity;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * The authenticated user's own view of themselves, and the row the Super Admin
 * listing returns. Private by definition — use {@link PublicUserResponse} anywhere
 * another user can read the projection.
 *
 * <p>{@code badgeType} is the credential an administrator approved and is null
 * whenever {@code verified} is false. A user holds at most one badge; it is read from
 * {@code users.badge_type}, which the verification workflow writes in the same
 * transaction as the decision, so a revocation takes effect the moment it is recorded.
 * Like the public badge it is a trust signal and never a permission: role checks read
 * {@link UserRoles} alone.
 */
@Builder
public record UserResponse(
        String displayName,
        String email,
        UserRoles role,
        UserStatus accountStatus,
        boolean verified,
        VerificationType badgeType,
        LocalDateTime createdAt) {
}
