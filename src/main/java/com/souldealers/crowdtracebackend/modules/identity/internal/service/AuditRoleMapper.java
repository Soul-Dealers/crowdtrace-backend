package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditVerificationType;

/** Maps identity-owned enums into the audit module's closed vocabulary. */
final class AuditRoleMapper {

    private AuditRoleMapper() { }

    static AuditActor actor(User user) {
        return AuditActor.user(user.getId(), actorRole(user.getRole()));
    }

    static AuditActorRole actorRole(UserRoles role) {
        return switch (role) {
            case REGISTERED_USER -> AuditActorRole.REGISTERED_USER;
            case MODERATOR -> AuditActorRole.MODERATOR;
            case SUPER_ADMIN -> AuditActorRole.SUPER_ADMIN;
        };
    }

    static AuditVerificationType verificationType(VerificationType type) {
        return switch (type) {
            case POLICE -> AuditVerificationType.POLICE;
            case NGO -> AuditVerificationType.NGO;
            case SUBJECT_MATTER_EXPERT -> AuditVerificationType.SUBJECT_MATTER_EXPERT;
        };
    }
}
