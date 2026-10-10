package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.VerificationType;
import com.souldealers.crowdtracebackend.modules.identity.UserStatus;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditVerificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRoleMapperTest {

    @Test
    void mapsEveryIdentityRoleToItsAuditRole() {
        assertThat(AuditRoleMapper.actor(user(UserRoles.REGISTERED_USER)).role())
                .isEqualTo(AuditActorRole.REGISTERED_USER);
        assertThat(AuditRoleMapper.actor(user(UserRoles.MODERATOR)).role()).isEqualTo(AuditActorRole.MODERATOR);
        assertThat(AuditRoleMapper.actor(user(UserRoles.SUPER_ADMIN)).role()).isEqualTo(AuditActorRole.SUPER_ADMIN);
    }

    @Test
    void mapsEveryVerificationTypeToItsAuditCode() {
        assertThat(AuditRoleMapper.verificationType(VerificationType.POLICE))
                .isEqualTo(AuditVerificationType.POLICE);
        assertThat(AuditRoleMapper.verificationType(VerificationType.NGO)).isEqualTo(AuditVerificationType.NGO);
        assertThat(AuditRoleMapper.verificationType(VerificationType.SUBJECT_MATTER_EXPERT))
                .isEqualTo(AuditVerificationType.SUBJECT_MATTER_EXPERT);
    }

    private User user(UserRoles role) {
        return User.builder().id(12L).role(role).accountStatus(UserStatus.ACTIVE).build();
    }
}
