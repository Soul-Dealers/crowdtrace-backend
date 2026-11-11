package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DuplicateMatcher {
    private static final String ALGORITHM_VERSION = "v1";
    private final NameNormalizer normalizer;
    private final DuplicateProperties properties;

    public Optional<DuplicateMatch> match(String name, LocalDate date, String candidateName, LocalDate candidateDate) {
        if (date == null || candidateDate == null) return Optional.empty();
        long days = Math.abs(ChronoUnit.DAYS.between(date, candidateDate));
        int window = properties.getDateWindowDays();
        if (days > window) return Optional.empty();

        String normalized = normalizer.normalize(name);
        String candidate = normalizer.normalize(candidateName);
        if (normalized.isEmpty() || candidate.isEmpty()) return Optional.empty();
        boolean exact = normalized.equals(candidate);
        if ((!normalized.contains(" ") || !candidate.contains(" ")) && !exact) return Optional.empty();

        int[] left = normalized.codePoints().toArray();
        int[] right = candidate.codePoints().toArray();
        int length = Math.max(left.length, right.length);
        int matching = length - levenshtein(left, right);
        if (1000L * matching < (long) properties.getNameThresholdPermille() * length) return Optional.empty();

        MatchReason nameReason = exact
                ? (normalizer.normalizeInOrder(name).equals(normalizer.normalizeInOrder(candidateName))
                    ? MatchReason.NAME_EXACT : MatchReason.NAME_REORDERED)
                : MatchReason.NAME_FUZZY;
        long dateScale = window + 1L;
        long numerator = 80L * matching * dateScale + 20L * (dateScale - days) * length;
        int confidence = roundHalfUp(numerator, length * dateScale);
        int similarity = roundHalfUp(1000L * matching, length);
        return Optional.of(new DuplicateMatch(confidence, similarity, (int) days,
                List.of(nameReason, days == 0 ? MatchReason.LAST_SEEN_EXACT : MatchReason.LAST_SEEN_NEAR),
                ALGORITHM_VERSION));
    }

    private int roundHalfUp(long numerator, long denominator) {
        return (int) ((2 * numerator + denominator) / (2 * denominator));
    }

    private int levenshtein(int[] left, int[] right) {
        int[] previous = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length; i++) {
            int[] current = new int[right.length + 1];
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left[i - 1] == right[j - 1] ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length];
    }
}
