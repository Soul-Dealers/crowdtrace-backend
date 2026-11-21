package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.CursorResponse;

/** Super Admin account management: list users, change a moderator role, deactivate. */
public interface AdminUserService {

    /** Newest first; {@code role}, {@code status} and {@code q} are optional; size is clamped to 1..50. */
    CursorResponse<AdminUserResponse> list(UserRoles role, UserStatus status, String q, String cursor, int size);
}
