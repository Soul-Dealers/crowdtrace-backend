package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateMatcherTest {
    private final LocalDate date = LocalDate.of(2026, 9, 30);
    private final DuplicateProperties properties = new DuplicateProperties();
    private final DuplicateMatcher matcher = new DuplicateMatcher(new NameNormalizer(), properties);

    @Test
    void exactNameAndDateScore100() {
        assertThat(matcher.match("Kofi Mensah", date, "KOFÍ Mensah", date)).get()
                .isEqualTo(new DuplicateMatch(100, 1000, 0,
                        List.of(MatchReason.NAME_EXACT, MatchReason.LAST_SEEN_EXACT), "v1"));
    }

    @Test
    void reorderedNameThreeDaysApartScores93() {
        assertThat(matcher.match("Kofi Mensah", date, "Mensah, Kofi", date.plusDays(3))).get()
                .isEqualTo(new DuplicateMatch(93, 1000, 3,
                        List.of(MatchReason.NAME_REORDERED, MatchReason.LAST_SEEN_NEAR), "v1"));
    }

    @Test
    void fuzzyNameOneDayApartScores90() {
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensa", date.minusDays(1))).get()
                .isEqualTo(new DuplicateMatch(90, 909, 1,
                        List.of(MatchReason.NAME_FUZZY, MatchReason.LAST_SEEN_NEAR), "v1"));
    }

    @Test
    void rejectsFalsePositivesAndMissingDates() {
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensah", date.plusDays(8))).isEmpty();
        assertThat(matcher.match("Kofi Mensah", date, "Kwame Mensah", date)).isEmpty();
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Owusu", date)).isEmpty();
        assertThat(matcher.match("Ama", date, "Ami", date)).isEmpty();
        assertThat(matcher.match("Ama", date, "AMA", date)).isPresent();
        assertThat(matcher.match("Kofi Mensah", null, "Kofi Mensah", date)).isEmpty();
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensah", null)).isEmpty();
        assertThat(matcher.match("...", date, "...", date)).isEmpty();
    }

    @Test
    void sameInputsSameResult() {
        Optional<DuplicateMatch> first = matcher.match("Kofi Mensah", date, "Mensah Kofi", date.plusDays(3));
        for (int i = 0; i < 100; i++) {
            assertThat(matcher.match("Kofi Mensah", date, "Mensah Kofi", date.plusDays(3))).isEqualTo(first);
        }
    }

    @Test
    void candidateOrderDoesNotChangeResult() {
        List<String> names = List.of("Kofi Mensah", "Kofi Mensa", "Kofi Owusu");
        List<DuplicateMatch> forward = names.stream().flatMap(n -> matcher.match("Kofi Mensah", date, n, date).stream()).toList();
        List<DuplicateMatch> reverse = names.reversed().stream().flatMap(n -> matcher.match("Kofi Mensah", date, n, date).stream()).toList();
        assertThat(reverse).containsExactlyInAnyOrderElementsOf(forward);
    }

    @Test
    void usesConfiguredThresholdWithoutRoundingSimilarityBeforeComparison() {
        properties.setNameThresholdPermille(910);
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensa", date)).isEmpty();
        properties.setNameThresholdPermille(909);
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensa", date)).isPresent();
        properties.setDateWindowDays(2);
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensah", date.plusDays(3))).isEmpty();
    }

    @Test
    void roundedPermilleCannotTurnANearMissIntoAMatch() {
        assertThat(matcher.match("a".repeat(207) + " x", date,
                "a".repeat(186) + "b".repeat(21) + " x", date)).isEmpty();
    }

    @Test
    void widenedWindowKeepsScoresInRange() {
        properties.setDateWindowDays(14);
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensah", date.plusDays(14))).get()
                .extracting(DuplicateMatch::confidence).isEqualTo(81);
        properties.setDateWindowDays(0);
        assertThat(matcher.match("Kofi Mensah", date, "Kofi Mensah", date)).get()
                .extracting(DuplicateMatch::confidence).isEqualTo(100);
    }

    @Test
    void similarityUsesUnicodeCodePoints() {
        assertThat(matcher.match("𐐀bcdefghij X", date, "𐐀bcdefghi X", date)).get()
                .extracting(DuplicateMatch::nameSimilarity).isEqualTo(917);
    }
}
