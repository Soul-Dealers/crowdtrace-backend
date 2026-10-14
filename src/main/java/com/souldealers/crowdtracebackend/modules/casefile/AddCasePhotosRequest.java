package com.souldealers.crowdtracebackend.modules.casefile;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddCasePhotosRequest(@NotNull @Min(0) Long expectedCaseVersion,
                                  @NotEmpty @Size(max = 5) List<@NotNull @Positive Long> photoFileIds) { }
