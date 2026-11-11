package com.souldealers.crowdtracebackend.shared.audit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionTest {
    private static final Pattern STORAGE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*(\\.[A-Z][A-Z0-9_]*)*$");

    @Test
    void actionCodesAreUniqueAndMatchTheStorageConstraint() {
        var codes = new HashSet<String>();

        for (AuditAction action : AuditAction.values()) {
            assertThat(codes.add(action.code())).as("unique action code %s", action.code()).isTrue();
            assertThat(action.code()).matches(STORAGE_CODE).hasSizeLessThanOrEqualTo(64);
        }
    }

    @Test
    void everyActionHasATargetAndSchema() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.targetType()).as(action.name()).isNotNull();
            assertThat(action.schema()).as(action.name()).isNotNull();
        }
    }

    @Test
    void targetCodesMatchTheStorageConstraintAndColumnLimit() {
        for (AuditTargetType targetType : AuditTargetType.values()) {
            assertThat(targetType.code()).matches(STORAGE_CODE).hasSizeLessThanOrEqualTo(32);
        }
    }
}
