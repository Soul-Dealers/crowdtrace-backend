package com.souldealers.crowdtracebackend.modules.casefile;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RestoreCaseRequest(@NotNull @Min(0) Long expectedCaseVersion) { }
