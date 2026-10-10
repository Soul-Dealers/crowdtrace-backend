package com.souldealers.crowdtracebackend.shared.audit;

/** Actor identity and role captured at the point an audit event is recorded. */
public record AuditActor(Long id, AuditActorRole role) {

    public AuditActor {
        if (role == null) {
            throw new IllegalArgumentException("Audit actor role is required");
        }
        if ((id == null) != (role == AuditActorRole.SYSTEM)) {
            throw new IllegalArgumentException("Audit actor id must be absent exactly for SYSTEM role");
        }
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("Audit actor id must be positive");
        }
    }

    public static AuditActor user(long id, AuditActorRole role) {
        return new AuditActor(id, role);
    }

    public static AuditActor system() {
        return new AuditActor(null, AuditActorRole.SYSTEM);
    }
}
