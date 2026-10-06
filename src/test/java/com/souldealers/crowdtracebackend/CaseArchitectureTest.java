package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseArchitectureTest {
    @Test
    void noControllerExposesAnEntity() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.souldealers.crowdtracebackend");
        entityRule().check(classes);
    }

    @Test
    void guardRejectsAnEntityParameterOrReturnType() {
        JavaClasses classes = new ClassFileImporter().importClasses(LeakyController.class, CaseRecord.class);
        assertThatThrownBy(() -> entityRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("exposes entity");
    }

    @RestController
    @Profile("architecture-fixture")
    static class LeakyController {
        public CaseRecord read(CaseRecord input) { return input; }
    }

    private static ArchRule entityRule() {
        return methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should(new ArchCondition<>("not return or accept a JPA entity") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        Stream.concat(Stream.of(method.getRawReturnType()), method.getRawParameterTypes().stream())
                                .filter(type -> type.isAnnotatedWith(Entity.class))
                                .forEach(type -> events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " exposes entity " + type.getName())));
                    }
                }).allowEmptyShould(true);
    }

}
