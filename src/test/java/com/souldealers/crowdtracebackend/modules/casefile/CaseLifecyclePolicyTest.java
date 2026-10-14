package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class CaseLifecyclePolicyTest {
    private static final Set<String> REPORTER_TRANSITIONS = Set.of(
            "MISSING->FOUND_SAFE", "MISSING->FOUND_DECEASED", "MISSING->CLOSED");

    @ParameterizedTest
    @MethodSource("statusTransitions")
    void allowsOnlyTheSpecifiedOutcomeTransitions(ReviewStatus review, CaseStatus current,
                                                   CaseStatus target, boolean admin, boolean allowed) {
        if (allowed) {
            assertThatCode(() -> CaseLifecyclePolicy.requireStatusChange(review, current, target, admin))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> CaseLifecyclePolicy.requireStatusChange(review, current, target, admin))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("reviewStatuses")
    void permitsTakedownOnlyFromApproved(ReviewStatus review) {
        if (review == ReviewStatus.APPROVED) {
            assertThatCode(() -> CaseLifecyclePolicy.requireTakedown(review)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> CaseLifecyclePolicy.requireTakedown(review))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("reviewStatuses")
    void permitsRestoreOnlyFromTakenDown(ReviewStatus review) {
        if (review == ReviewStatus.TAKEN_DOWN) {
            assertThatCode(() -> CaseLifecyclePolicy.requireRestore(review)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> CaseLifecyclePolicy.requireRestore(review))
                    .isInstanceOf(ConflictException.class);
        }
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> reviewStatuses() {
        return Stream.of(ReviewStatus.values()).map(status -> arguments(status));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> statusTransitions() {
        return Stream.of(ReviewStatus.values()).flatMap(review -> Stream.of(CaseStatus.values())
                .flatMap(current -> Stream.of(CaseStatus.values()).flatMap(target -> Stream.of(false, true)
                        .map(admin -> arguments(review, current, target, admin,
                                allowed(review, current, target, admin))))));
    }

    private static boolean allowed(ReviewStatus review, CaseStatus current, CaseStatus target, boolean admin) {
        if (review != ReviewStatus.APPROVED || current == target) return false;
        if (admin) return true;
        return REPORTER_TRANSITIONS.contains(current + "->" + target);
    }
}
