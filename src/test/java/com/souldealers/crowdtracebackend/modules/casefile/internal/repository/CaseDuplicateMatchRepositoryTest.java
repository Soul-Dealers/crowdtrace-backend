package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseDuplicateMatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;

class CaseDuplicateMatchRepositoryTest extends CasePostgresTestSupport {
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseDuplicateMatchRepository matches;

    @Test
    void aMatchRoundTripsWithoutNameSnapshots() {
        long reporter = user("match@example.com");
        long original = cases.saveAndFlush(aCase(reporter).build()).getId();
        long submitted = cases.saveAndFlush(aCase(reporter).build()).getId();
        CaseDuplicateMatch saved = matches.saveAndFlush(match(submitted, original));

        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted))
                .singleElement().satisfies(row -> {
                    assertThat(row.getId()).isEqualTo(saved.getId());
                    assertThat(row.getConfidence()).isEqualTo(100);
                    assertThat(row.getNameSimilarity()).isEqualTo(1000);
                    assertThat(row.getDayDifference()).isZero();
                    assertThat(row.getReasons()).isEqualTo("NAME_EXACT,LAST_SEEN_EXACT");
                    assertThat(row.getAlgorithmVersion()).isEqualTo("v1");
                    assertThat(row.getDetectedAt()).isNotNull();
                });
        assertThat(matches.existsByCaseIdAndMatchedCaseId(submitted, original)).isTrue();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='case_duplicate_matches'", String.class))
                .containsExactlyInAnyOrder("id", "case_id", "matched_case_id", "confidence", "name_similarity",
                        "day_difference", "reasons", "algorithm_version", "detected_at");
    }

    @Test
    void duplicateDirectionalPairIsRejected() {
        long reporter = user("pair@example.com");
        long first = cases.saveAndFlush(aCase(reporter).build()).getId();
        long second = cases.saveAndFlush(aCase(reporter).build()).getId();
        matches.saveAndFlush(match(second, first));
        assertThatThrownBy(() -> matches.saveAndFlush(match(second, first)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void selfMatchIsRejected() {
        long reporter = user("self@example.com");
        long id = cases.saveAndFlush(aCase(reporter).build()).getId();
        assertThatThrownBy(() -> matches.saveAndFlush(match(id, id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CaseDuplicateMatch match(long caseId, long matchedCaseId) {
        return CaseDuplicateMatch.builder().caseId(caseId).matchedCaseId(matchedCaseId)
                .confidence(100).nameSimilarity(1000).dayDifference(0)
                .reasons("NAME_EXACT,LAST_SEEN_EXACT").algorithmVersion("v1").build();
    }
}
