package com.souldealers.crowdtracebackend.shared.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditActorTest {

    @Test
    void systemActorHasNoIdAndSystemRole() {
        AuditActor actor = AuditActor.system();

        assertThat(actor.id()).isNull();
        assertThat(actor.role()).isEqualTo(AuditActorRole.SYSTEM);
    }

    @Test
    void systemRoleCannotHaveAUserId() {
        assertThatThrownBy(() -> AuditActor.user(1, AuditActorRole.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userActorRequiresAPositiveIdAndNonSystemRole() {
        assertThatThrownBy(() -> AuditActor.user(0, AuditActorRole.MODERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActor.user(-1, AuditActorRole.MODERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActor.user(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditActor(null, AuditActorRole.REGISTERED_USER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
