package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.shared.ApiResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @Test
    void guardRejectsWrappedEntityResponses() {
        JavaClasses classes = new ClassFileImporter().importClasses(WrappedController.class, CaseRecord.class);
        assertThatThrownBy(() -> entityRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContainingAll("read()", "nested()", "exposes entity");
    }

    @Test
    void guardRejectsWrappedEntityParameters() {
        JavaClasses classes = new ClassFileImporter().importClasses(WrappedInputController.class, CaseRecord.class);
        assertThatThrownBy(() -> entityRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("exposes entity");
    }

    @RestController
    @Profile("architecture-fixture")
    static class WrappedController {
        public ApiResponse<CaseRecord> read() { return null; }
        public ResponseEntity<List<CaseRecord>> nested() { return null; }
    }

    @RestController
    @Profile("architecture-fixture")
    static class WrappedInputController {
        public void write(List<CaseRecord> input) {}
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
                        Stream.concat(Stream.of(method.getReturnType()), method.getParameterTypes().stream())
                                .flatMap(type -> type.getAllInvolvedRawTypes().stream())
                                .distinct()
                                .filter(type -> type.isAnnotatedWith(Entity.class))
                                .forEach(type -> events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " exposes entity " + type.getName())));
                    }
                }).allowEmptyShould(true);
    }

}
