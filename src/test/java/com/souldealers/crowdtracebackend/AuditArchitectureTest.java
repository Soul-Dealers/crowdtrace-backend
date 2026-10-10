package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.shared.audit.internal.AuditEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditArchitectureTest {

    private static ArchRule internalAuditTypesStayInternal() {
        return noClasses().that().resideOutsideOfPackages("..shared.audit..")
                .should().dependOnClassesThat().resideInAPackage("..shared.audit.internal..");
    }

    @Test
    void productionTypesOutsideAuditDoNotDependOnAuditInternals() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.souldealers.crowdtracebackend");

        internalAuditTypesStayInternal().check(production);
    }

    @Test
    void guardRejectsAnExternalDependencyOnAuditInternals() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ForbiddenAuditConsumer.class);

        assertThatThrownBy(() -> internalAuditTypesStayInternal().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AuditEvent.class.getName());
    }

    static class ForbiddenAuditConsumer {
        private AuditEvent event;
    }
}
