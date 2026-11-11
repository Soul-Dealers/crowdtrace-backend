package com.souldealers.crowdtracebackend.modules.identity;

/** The acting user as other modules may see it: id and role only. */
public record ActorView(Long userId, UserRoles role) { }
