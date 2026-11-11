package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.LastSeenDateValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = LastSeenDateValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidLastSeenDate {

    String message() default "must be between 1900-01-01 and today (UTC)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
