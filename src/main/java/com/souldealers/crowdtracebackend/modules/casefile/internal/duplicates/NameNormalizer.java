package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class NameNormalizer {
    public String normalize(String name) {
        String ordered = normalizeInOrder(name);
        if (ordered.isEmpty()) return ordered;
        return Arrays.stream(ordered.split(" ")).sorted().collect(Collectors.joining(" "));
    }

    String normalizeInOrder(String name) {
        if (name == null) return "";
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\p{P}", " ").replaceAll("[\\s\\p{Z}]+", " ").trim();
    }
}
