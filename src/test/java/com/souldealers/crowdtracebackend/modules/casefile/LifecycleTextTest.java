package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleTextTest {
    @Test
    void stripsUnicodeWhitespaceFromBothEnds() {
        assertThat(LifecycleText.normalize("\u2003  Family notified\u2003", "Closing statement"))
                .isEqualTo("Family notified");
    }

    @Test
    void rejectsTextThatIsBlankAfterUnicodeStripping() {
        assertThatThrownBy(() -> LifecycleText.normalize("\u2003\t\n", "Closing statement"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptsTwoThousandCharactersAfterStripping() {
        String text = "x".repeat(2000);
        assertThat(LifecycleText.normalize(text, "Closing statement")).isEqualTo(text);
    }

    @Test
    void rejectsTwoThousandAndOneCharacters() {
        assertThatThrownBy(() -> LifecycleText.normalize("x".repeat(2001), "Closing statement"))
                .isInstanceOf(ValidationException.class);
    }
}
