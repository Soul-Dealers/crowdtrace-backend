package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.souldealers.crowdtracebackend.modules.casefile.CaseStatus.MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicCaseSearchQueryTest {
    @Test
    void validatesAndTrimsName() {
        assertThatThrownBy(() -> query("   ", null, null, null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("name must be 3-100 characters");
        assertThatThrownBy(() -> query("ab", null, null, null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThat(query(" abc ", null, null, null, null, null, null, null, null).name()).isEqualTo("abc");
        assertThat(query("x".repeat(100), null, null, null, null, null, null, null, null).name()).hasSize(100);
        assertThatThrownBy(() -> query("x".repeat(101), null, null, null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validatesAgeRange() {
        assertThatThrownBy(() -> query(null, null, null, -1, null, null, null, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> query(null, null, null, 131, null, null, null, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> query(null, null, null, null, -1, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("ageMax must be between 0 and 130");
        assertThatThrownBy(() -> query(null, null, null, null, 131, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("ageMax must be between 0 and 130");
        assertThat(query(null, null, null, 0, 0, null, null, null, null).ageMin()).isZero();
        assertThat(query(null, null, null, 130, 130, null, null, null, null).ageMax()).isEqualTo(130);
        assertThatThrownBy(() -> query(null, null, null, 40, 39, null, null, null, null)).isInstanceOf(ValidationException.class);
        assertThat(query(null, null, null, 40, 40, null, null, null, null).ageMax()).isEqualTo(40);
    }

    @Test
    void validatesDateOrderAndSupportedYears() {
        assertThatThrownBy(() -> query(null, null, null, null, null, LocalDate.parse("2026-02-02"),
                LocalDate.parse("2026-02-01"), null, null)).isInstanceOf(ValidationException.class);
        assertThat(query(null, null, null, null, null, LocalDate.parse("2026-02-02"),
                LocalDate.parse("2026-02-02"), null, null)).isNotNull();
        assertThatThrownBy(() -> query(null, null, null, null, null, null, null,
                LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-01"))).isInstanceOf(ValidationException.class);
        assertThat(query(null, null, null, null, null, null, null,
                LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-02"))).isNotNull();
        assertThatThrownBy(() -> query(null, null, null, null, null, LocalDate.of(0, 1, 1), null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("dates must be between 0001-01-01 and 9999-12-31");
        assertThatThrownBy(() -> query(null, null, null, null, null, null, null, null, LocalDate.of(10000, 1, 1)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> query(null, null, null, null, null, LocalDate.MIN, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> query(null, null, null, null, null, null, null, null, LocalDate.MAX))
                .isInstanceOf(ValidationException.class);
        assertThat(query(null, null, null, null, null, null, null, null, LocalDate.of(9999, 12, 31))
                .approvedToExclusive()).isEqualTo(java.time.LocalDateTime.parse("+10000-01-01T00:00"));
    }

    @Test
    void normalizesStatusesAndDefensivelyCopiesThem() {
        assertThat(PublicCaseSearchQuery.all().statuses()).containsExactlyInAnyOrder(CaseStatus.values());
        assertThat(query(null, null, Set.of(), null, null, null, null, null, null).statuses())
                .containsExactlyInAnyOrder(CaseStatus.values());
        assertThat(query(null, null, Set.of(MISSING), null, null, null, null, null, null).statuses()).containsExactly(MISSING);
        Set<CaseStatus> mutable = new HashSet<>(Set.of(MISSING));
        PublicCaseSearchQuery query = query(null, null, mutable, null, null, null, null, null, null);
        mutable.clear();
        assertThat(query.statuses()).containsExactly(MISSING);
        assertThatThrownBy(() -> query.statuses().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> query(null, null, new HashSet<>(Arrays.asList(MISSING, null)), null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("status must be one of MISSING, FOUND_SAFE, FOUND_DECEASED, CLOSED");
    }

    @Test
    void escapesNameLikePattern() {
        assertThat(query("50%_\\x", null, null, null, null, null, null, null, null).nameLikePattern()).isEqualTo("%50\\%\\_\\\\x%");
        assertThat(query("Kofi", null, null, null, null, null, null, null, null).nameLikePattern()).isEqualTo("%kofi%");
    }

    @Test
    void makesApprovedToExclusiveAndHandlesLeapDay() {
        assertThat(query(null, null, null, null, null, null, null, LocalDate.parse("2026-02-28"), null)
                .approvedFromInclusive()).isEqualTo(java.time.LocalDateTime.parse("2026-02-28T00:00"));
        assertThat(query(null, null, null, null, null, null, null, null, LocalDate.parse("2026-02-28"))
                .approvedToExclusive()).isEqualTo(java.time.LocalDateTime.parse("2026-03-01T00:00"));
        assertThat(query(null, null, null, null, null, null, null, null, LocalDate.parse("2028-02-29"))
                .approvedToExclusive()).isEqualTo(java.time.LocalDateTime.parse("2028-03-01T00:00"));
    }

    private PublicCaseSearchQuery query(String name, GhanaRegion region, Set<CaseStatus> statuses, Integer ageMin,
                                        Integer ageMax, LocalDate lastSeenFrom, LocalDate lastSeenTo,
                                        LocalDate approvedFrom, LocalDate approvedTo) {
        return new PublicCaseSearchQuery(name, region, null, statuses, ageMin, ageMax, lastSeenFrom, lastSeenTo,
                approvedFrom, approvedTo);
    }
}
