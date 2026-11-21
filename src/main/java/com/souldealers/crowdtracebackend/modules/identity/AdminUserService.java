package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

/** Super Admin account management: list users, change a moderator role, deactivate. */
public interface AdminUserService {

    /** Newest first; {@code role}, {@code status} and {@code q} are optional; size is clamped to 1..50. */
    CursorResponse<AdminUserResponse> list(UserRoles role, UserStatus status, String q, String cursor, int size);

    /** Moves an active non-Super-Admin between {@code REGISTERED_USER} and {@code MODERATOR}. Never the caller. */
    AdminUserResponse changeRole(String actorEmail, long userId, UserRoles role);

    /** Deactivates any non-Super-Admin except the caller and revokes their tokens. */
    AdminUserResponse deactivate(String actorEmail, long userId);
}
