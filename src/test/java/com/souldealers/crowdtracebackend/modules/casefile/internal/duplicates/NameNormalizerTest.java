package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NameNormalizerTest {
    private final NameNormalizer normalizer = new NameNormalizer();

    @Test
    void normalizesCaseWhitespaceAccentsAndPunctuation() {
        assertThat(normalizer.normalize("  KOFÍ  Mensah ")).isEqualTo("kofi mensah");
        assertThat(normalizer.normalize("Mensah, Kofi")).isEqualTo("kofi mensah");
        assertThat(normalizer.normalize("Ɛfua Ɔsei")).isEqualTo("ɔsei ɛfua");
        assertThat(normalizer.normalize("Ama\u00a0Owusu")).isEqualTo("ama owusu");
    }

    @Test
    void keepsRepeatedTokensAndRejectsEmptyNames() {
        assertThat(normalizer.normalize("Ama Ama Owusu")).isEqualTo("ama ama owusu")
                .isNotEqualTo(normalizer.normalize("Ama Owusu"));
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize(" , ")).isEmpty();
    }
}
