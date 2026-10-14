package com.souldealers.crowdtracebackend.modules.governance;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDecisionRequestValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void approvalNeedsAVersionAndAcceptsMissingOrBlankNotesUpToTheCap() {
        for (String notes : Arrays.asList(null, "", "  ", "ok", "x".repeat(2000))) {
            assertThat(validator.validate(new ApproveCaseRequest(3L, notes))).isEmpty();
        }
        assertThat(validator.validate(new ApproveCaseRequest(null, null))).isNotEmpty();
        assertThat(validator.validate(new ApproveCaseRequest(-1L, null))).isNotEmpty();
        assertThat(validator.validate(new ApproveCaseRequest(3L, "x".repeat(2001)))).isNotEmpty();
    }

    @Test
    void rejectionNeedsAVersionAndNotesThatAreNotBlankAfterUnicodeTrim() {
        assertThat(validator.validate(new RejectCaseRequest(0L, "Needs a recent photo"))).isEmpty();
        assertThat(validator.validate(new RejectCaseRequest(0L, "x".repeat(2000)))).isEmpty();
        for (String notes : Arrays.asList(null, "", "   ", "  \t\n", "x".repeat(2001))) {
            assertThat(validator.validate(new RejectCaseRequest(0L, notes))).as("notes %s", notes).isNotEmpty();
        }
        assertThat(validator.validate(new RejectCaseRequest(null, "Needs a recent photo"))).isNotEmpty();
        assertThat(validator.validate(new RejectCaseRequest(-1L, "Needs a recent photo"))).isNotEmpty();
    }
}
