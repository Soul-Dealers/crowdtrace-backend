package com.souldealers.crowdtracebackend.modules.casefile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

@Builder(toBuilder = true)
public record CaseSubmissionRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotNull @Min(0) @Max(130) Integer age,
        @NotNull Gender gender,
        @NotNull @ValidLastSeenDate LocalDate lastSeenDate,
        @NotNull GhanaRegion region,
        @NotBlank @Size(max = 500) String lastSeenLocation,
        @NotBlank String physicalDescription,
        @NotBlank String clothing,
        @NotBlank String circumstances,
        @NotBlank @Size(max = 32) String publicContactNumber,
        @NotNull @Valid SensitiveDetailsInput sensitiveDetails,
        @NotNull @Valid ConsentInput consent,
        @NotEmpty List<@NotNull @Positive Long> reportFileIds,
        @Size(max = 5) List<@NotNull @Positive Long> photoFileIds) {

    @Builder(toBuilder = true)
    public record SensitiveDetailsInput(
            @NotBlank @Size(max = 100) String reporterRelationship,
            String medicalConditions,
            String knownAssociates,
            String vehicleInfo,
            String socialMediaHandles) {
    }

    @Builder(toBuilder = true)
    public record ConsentInput(
            @NotNull @AssertTrue Boolean accepted,
            @NotBlank @Size(max = 32) String version,
            @NotNull ConsentSource source) {
    }
}
