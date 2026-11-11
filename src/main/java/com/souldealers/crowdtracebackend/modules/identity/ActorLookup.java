package com.souldealers.crowdtracebackend.modules.identity;

/** Resolves the authenticated principal to the acting user for cross-module decisions. */
public interface ActorLookup {

    /**
     * @throws com.souldealers.crowdtracebackend.shared.UnauthorizedException if the user is unknown,
     *         not active or deleted
     */
    ActorView byEmail(String email);
}
