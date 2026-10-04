package com.souldealers.crowdtracebackend.modules.identity;

/**
 * The only identity projection other modules may show publicly.
 *
 * <p>Carries the pseudonym and the verified badge, and nothing else. Email, role,
 * account status, and verification evidence are private to the identity module —
 * keeping them off this record is what makes pseudonymous participation safe.
 * Use {@link UserResponse} for the authenticated user's own view of themselves.
 *
 * <p>{@code badgeType} is the credential an administrator confirmed, and is null
 * whenever {@code verified} is false. The product spec lists "verified badge status
 * + type" as a public account field: a reader is meant to see <em>why</em> a
 * contributor is credible, not only that they are. The badge is a trust signal and
 * never a permission: the product spec is explicit that there is no privileged
 * investigator role, so a badge must never be read as an authorization decision.
 * A user holds at most one badge. It is read from {@code users.badge_type}, which the
 * CT-010 request and decision workflow writes in the same transaction as the decision.
 *
 * <p>Intentionally has no caller yet. It is the seam the {@code casefile} and
 * {@code community} modules will render authors through; {@code /api/public/**}
 * belongs to discovery in phase 4. Do not remove as dead code.
 */
public record PublicUserResponse(
        String displayName,
        boolean verified,
        VerificationType badgeType) {
}
