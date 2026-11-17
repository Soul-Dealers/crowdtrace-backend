package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;

/** Storage keys are server-made: they round-trip for their own purpose and nothing else gets through. */
@Tag("fast")
class StorageKeyPropertyTest {

    @Property
    void aGeneratedKeyParsesBackForItsOwnPurpose(@ForAll CaseFilePurpose purpose) {
        StorageKey key = StorageKey.generate(purpose);

        assertThat(StorageKey.parse(key.value(), purpose)).isEqualTo(key);
    }

    @Property
    void aKeyIsRejectedForAnyOtherPurpose(@ForAll CaseFilePurpose made, @ForAll CaseFilePurpose expected) {
        Assume.that(!made.storagePrefix().equals(expected.storagePrefix()));
        String value = StorageKey.generate(made).value();

        assertThatThrownBy(() -> StorageKey.parse(value, expected)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void anyAcceptedStringHasTheServerFormat(@ForAll String value) {
        try {
            new StorageKey(value);
        } catch (IllegalArgumentException rejected) {
            return;
        }
        assertThat(value).matches("^(reports|photos)/[0-9a-f-]{36}$");
    }

    @Property
    void theKeyNeverAppearsInLogs(@ForAll CaseFilePurpose purpose) {
        StorageKey key = StorageKey.generate(purpose);

        assertThat(key.toString()).doesNotContain(key.value());
    }
}
