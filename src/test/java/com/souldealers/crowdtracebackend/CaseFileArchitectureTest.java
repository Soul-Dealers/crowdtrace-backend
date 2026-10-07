package com.souldealers.crowdtracebackend;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileBoundaryFixtures;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.FileStorage;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseFileArchitectureTest {
    private static final String CASEFILE_PACKAGE = "com.souldealers.crowdtracebackend.modules.casefile";

    @Test
    void storageDetailsStayInsideCasefileInternals() {
        storageDependencyRule().check(productionClasses());
    }

    @Test
    void publicCasefileApiExposesNoStorageDetails() {
        publicApiRule().check(productionClasses());
    }

    @Test
    void dependencyGuardRejectsStoragePortKeyAndMetadataOutsideInternals() {
        JavaClasses classes = new ClassFileImporter().importClasses(
                ExternalStorageConsumer.class, FileStorage.class, StorageKey.class, StoredObject.class);
        assertThatThrownBy(() -> storageDependencyRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContainingAll("FileStorage", "StorageKey", "StoredObject");
    }

    @Test
    void publicApiGuardRejectsKeysInGenericFieldsAndMethodSignatures() {
        JavaClasses classes = new ClassFileImporter().importClasses(
                CaseFileBoundaryFixtures.GenericKeyExposure.class, StorageKey.class);
        assertThatThrownBy(() -> publicApiRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContainingAll("keys", "read()", "write(", "exposes storage type");
    }

    @Test
    void publicApiGuardRejectsSensitiveFieldNamesEvenWithoutStorageTypes() {
        JavaClasses classes = new ClassFileImporter().importClasses(CaseFileBoundaryFixtures.SensitiveFields.class);
        assertThatThrownBy(() -> publicApiRule().check(classes)).isInstanceOf(AssertionError.class)
                .hasMessageContainingAll("storageKey", "checksumSha256", "exposes sensitive field");
    }

    static class ExternalStorageConsumer {
        private FileStorage storage;
        private StorageKey key;
        private StoredObject metadata;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.souldealers.crowdtracebackend");
    }

    private static ArchRule storageDependencyRule() {
        return noClasses().that().resideOutsideOfPackage(CASEFILE_PACKAGE + ".internal..")
                .should().dependOnClassesThat(new DescribedPredicate<>("are storage internals") {
                    @Override
                    public boolean test(JavaClass type) {
                        return isStorageType(type);
                    }
                });
    }

    private static ArchRule publicApiRule() {
        return classes().that().resideInAPackage(CASEFILE_PACKAGE + "..")
                .and().resideOutsideOfPackage(CASEFILE_PACKAGE + ".internal..")
                .and().arePublic().should(new ArchCondition<>("not expose storage details") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        type.getFields().forEach(field -> {
                            if (field.getName().equals("storageKey") || field.getName().startsWith("checksum")) {
                                events.add(SimpleConditionEvent.violated(field,
                                        field.getFullName() + " exposes sensitive field " + field.getName()));
                            }
                            checkStorageTypes(field.getFullName(), Stream.of(field.getType()), events);
                        });
                        type.getMethods().forEach(method -> checkStorageTypes(method.getFullName(),
                                Stream.concat(Stream.of(method.getReturnType()), method.getParameterTypes().stream()),
                                events));
                        type.getConstructors().forEach(constructor -> checkStorageTypes(constructor.getFullName(),
                                constructor.getParameterTypes().stream(), events));
                    }
                });
    }

    private static void checkStorageTypes(String member, Stream<JavaType> types, ConditionEvents events) {
        types.flatMap(type -> type.getAllInvolvedRawTypes().stream()).distinct()
                .filter(CaseFileArchitectureTest::isStorageType)
                .forEach(type -> events.add(SimpleConditionEvent.violated(member,
                        member + " exposes storage type " + type.getName())));
    }

    private static boolean isStorageType(JavaClass type) {
        return type.isEquivalentTo(FileStorage.class) || type.isEquivalentTo(StorageKey.class)
                || type.isEquivalentTo(StoredObject.class);
    }
}
