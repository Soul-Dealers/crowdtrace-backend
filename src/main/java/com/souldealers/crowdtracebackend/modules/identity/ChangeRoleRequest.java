package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.NotNull;

/** The new role: {@code REGISTERED_USER} or {@code MODERATOR}. */
public record ChangeRoleRequest(@NotNull UserRoles role) {
}
