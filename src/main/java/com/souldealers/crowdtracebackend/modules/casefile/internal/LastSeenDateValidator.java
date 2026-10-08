package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.ValidLastSeenDate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.LocalDate;

public class LastSeenDateValidator implements ConstraintValidator<ValidLastSeenDate, LocalDate> {

    private static final LocalDate EARLIEST_DATE = LocalDate.of(1900, 1, 1);

    private final Clock clock;

    public LastSeenDateValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        return value == null || (!value.isBefore(EARLIEST_DATE) && !value.isAfter(LocalDate.now(clock)));
    }
}
