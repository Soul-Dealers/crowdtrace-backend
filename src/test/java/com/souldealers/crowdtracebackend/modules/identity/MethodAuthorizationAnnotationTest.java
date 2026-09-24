package com.souldealers.crowdtracebackend.modules.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MethodAuthorizationAnnotationTest {

    @Test
    void exposesTheThreeCrowdTraceAuthorizationPolicies() {
        assertThat(preAuthorizeValue(RequiresRegisteredUser.class))
                .isEqualTo("hasAnyAuthority('REGISTERED_USER', 'MODERATOR', 'SUPER_ADMIN')");
        assertThat(preAuthorizeValue(RequiresModerator.class))
                .isEqualTo("hasAnyAuthority('MODERATOR', 'SUPER_ADMIN')");
        assertThat(preAuthorizeValue(RequiresSuperAdmin.class))
                .isEqualTo("hasAuthority('SUPER_ADMIN')");
    }

    @Test
    void keepsAuthorizationAnnotationsAvailableAtRuntimeOnMethodsAndTypes() {
        assertRuntimeMethodAndTypeTarget(RequiresRegisteredUser.class);
        assertRuntimeMethodAndTypeTarget(RequiresModerator.class);
        assertRuntimeMethodAndTypeTarget(RequiresSuperAdmin.class);
    }

    private String preAuthorizeValue(Class<? extends Annotation> annotationType) {
        return annotationType.getAnnotation(PreAuthorize.class).value();
    }

    private void assertRuntimeMethodAndTypeTarget(Class<? extends Annotation> annotationType) {
        assertThat(annotationType.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Arrays.asList(annotationType.getAnnotation(Target.class).value()))
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }
}
