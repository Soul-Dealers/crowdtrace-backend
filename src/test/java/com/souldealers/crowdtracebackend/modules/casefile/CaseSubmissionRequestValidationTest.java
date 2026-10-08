package com.souldealers.crowdtracebackend.modules.casefile;

import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CaseSubmissionRequestValidationTest {

    private GenericApplicationContext context;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.registerBean(Clock.class,
                () -> Clock.fixed(Instant.parse("2026-10-08T00:30:00Z"), ZoneOffset.UTC));
        context.refresh();
        validator = new LocalValidatorFactoryBean();
        validator.setConstraintValidatorFactory(
                new SpringConstraintValidatorFactory(context.getAutowireCapableBeanFactory()));
        validator.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        validator.close();
        context.close();
    }

    @Test
    void acceptsACompleteRequestWithOptionalSensitiveFieldsAndPhotosOmitted() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "age", "gender", "lastSeenDate", "region",
            "lastSeenLocation", "physicalDescription", "clothing", "circumstances",
            "publicContactNumber", "sensitiveDetails", "sensitiveDetails.reporterRelationship",
            "consent", "consent.accepted", "consent.version", "consent.source", "reportFileIds"})
    void namesEachMissingRequiredField(String field) {
        assertThat(violationFields(withoutRequiredField(field))).containsExactly(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "lastSeenLocation", "physicalDescription", "clothing",
            "circumstances", "publicContactNumber", "sensitiveDetails.reporterRelationship", "consent.version"})
    void rejectsBlankRequiredStrings(String field) {
        assertThat(violationFields(withString(field, "  "))).containsExactly(field);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 131})
    void rejectsAgeOutsideTheAllowedRange(int age) {
        assertThat(violationFields(validRequest().toBuilder().age(age).build())).containsExactly("age");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 130})
    void acceptsAgeAtBothBoundaries(int age) {
        assertThat(validator.validate(validRequest().toBuilder().age(age).build())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1899-12-31", "2026-10-09"})
    void rejectsDatesOutsideTheAllowedRange(String date) {
        CaseSubmissionRequest request = validRequest().toBuilder().lastSeenDate(LocalDate.parse(date)).build();

        assertThat(violationFields(request)).containsExactly("lastSeenDate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1900-01-01", "2026-10-08"})
    void acceptsTheEarliestDateAndTodayInTheInjectedUtcClock(String date) {
        CaseSubmissionRequest request = validRequest().toBuilder().lastSeenDate(LocalDate.parse(date)).build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void namesConsentAcceptedWhenConsentIsDeclined() {
        CaseSubmissionRequest request = validRequest().toBuilder()
                .consent(validRequest().consent().toBuilder().accepted(false).build()).build();

        assertThat(violationFields(request)).containsExactly("consent.accepted");
    }

    @Test
    void namesReportFileIdsWhenTheReportListIsEmpty() {
        assertThat(violationFields(validRequest().toBuilder().reportFileIds(List.of()).build()))
                .containsExactly("reportFileIds");
    }

    @ParameterizedTest
    @CsvSource({"fullName,255", "lastSeenLocation,500", "publicContactNumber,32",
            "sensitiveDetails.reporterRelationship,100", "consent.version,32"})
    void rejectsStringsLongerThanTheDatabaseColumn(String field, int maximum) {
        assertThat(violationFields(withString(field, "x".repeat(maximum + 1)))).containsExactly(field);
    }

    @ParameterizedTest
    @CsvSource({"fullName,255", "lastSeenLocation,500", "publicContactNumber,32",
            "sensitiveDetails.reporterRelationship,100", "consent.version,32"})
    void acceptsStringsAtTheDatabaseColumnLimit(String field, int maximum) {
        assertThat(validator.validate(withString(field, "x".repeat(maximum)))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidFileIds")
    void rejectsNullOrNonPositiveReportFileIds(Long id) {
        CaseSubmissionRequest request = validRequest().toBuilder().reportFileIds(Arrays.asList(id)).build();

        assertThat(violationFields(request)).singleElement().asString().startsWith("reportFileIds[0]");
    }

    @ParameterizedTest
    @MethodSource("invalidFileIds")
    void rejectsNullOrNonPositivePhotoFileIds(Long id) {
        CaseSubmissionRequest request = validRequest().toBuilder().photoFileIds(Arrays.asList(id)).build();

        assertThat(violationFields(request)).singleElement().asString().startsWith("photoFileIds[0]");
    }

    @Test
    void acceptsMultipleReportsAndUpToFivePhotos() {
        CaseSubmissionRequest request = validRequest().toBuilder()
                .reportFileIds(List.of(1L, 2L))
                .photoFileIds(List.of(3L, 4L, 5L, 6L, 7L)).build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsAnEmptyPhotoList() {
        assertThat(validator.validate(validRequest().toBuilder().photoFileIds(List.of()).build())).isEmpty();
    }

    @Test
    void rejectsMoreThanFivePhotos() {
        CaseSubmissionRequest request = validRequest().toBuilder()
                .photoFileIds(List.of(2L, 3L, 4L, 5L, 6L, 7L)).build();

        assertThat(violationFields(request)).containsExactly("photoFileIds");
    }

    private Set<String> violationFields(CaseSubmissionRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static Stream<Long> invalidFileIds() {
        return Stream.of(null, 0L, -1L);
    }

    private static CaseSubmissionRequest withoutRequiredField(String field) {
        CaseSubmissionRequest request = validRequest();
        return switch (field) {
            case "age" -> request.toBuilder().age(null).build();
            case "gender" -> request.toBuilder().gender(null).build();
            case "lastSeenDate" -> request.toBuilder().lastSeenDate(null).build();
            case "region" -> request.toBuilder().region(null).build();
            case "sensitiveDetails" -> request.toBuilder().sensitiveDetails(null).build();
            case "consent" -> request.toBuilder().consent(null).build();
            case "consent.accepted" -> request.toBuilder()
                    .consent(request.consent().toBuilder().accepted(null).build()).build();
            case "consent.source" -> request.toBuilder()
                    .consent(request.consent().toBuilder().source(null).build()).build();
            case "reportFileIds" -> request.toBuilder().reportFileIds(null).build();
            default -> withString(field, null);
        };
    }

    private static CaseSubmissionRequest withString(String field, String value) {
        CaseSubmissionRequest request = validRequest();
        return switch (field) {
            case "fullName" -> request.toBuilder().fullName(value).build();
            case "lastSeenLocation" -> request.toBuilder().lastSeenLocation(value).build();
            case "physicalDescription" -> request.toBuilder().physicalDescription(value).build();
            case "clothing" -> request.toBuilder().clothing(value).build();
            case "circumstances" -> request.toBuilder().circumstances(value).build();
            case "publicContactNumber" -> request.toBuilder().publicContactNumber(value).build();
            case "sensitiveDetails.reporterRelationship" -> request.toBuilder()
                    .sensitiveDetails(request.sensitiveDetails().toBuilder().reporterRelationship(value).build())
                    .build();
            case "consent.version" -> request.toBuilder()
                    .consent(request.consent().toBuilder().version(value).build()).build();
            default -> throw new IllegalArgumentException("Unknown string field: " + field);
        };
    }

    private static CaseSubmissionRequest validRequest() {
        return CaseSubmissionRequest.builder()
                .fullName("Missing Person")
                .age(25)
                .gender(Gender.UNKNOWN)
                .lastSeenDate(LocalDate.of(2026, 10, 7))
                .region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra Central")
                .physicalDescription("Tall")
                .clothing("Blue shirt")
                .circumstances("Did not return home")
                .publicContactNumber("+233200000000")
                .sensitiveDetails(CaseSubmissionRequest.SensitiveDetailsInput.builder()
                        .reporterRelationship("Sibling").build())
                .consent(CaseSubmissionRequest.ConsentInput.builder()
                        .accepted(true).version("v1").source(ConsentSource.WEB).build())
                .reportFileIds(List.of(1L))
                .build();
    }
}
