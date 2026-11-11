package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.ConsentSource;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseConsentRepositoryTest extends CasePostgresTestSupport {
    @Autowired private CaseConsentRepository consents;
    @Autowired private CaseRecordRepository cases;

    @Test
    void keepsConsentHistoryInAcceptanceOrder() {
        long r = user("consent-history@example.com"), c = cases.save(aCase(r).build()).getId();
        consents.save(consent(r, c, "v3", ConsentSource.API, 10));
        consents.save(consent(r, c, "v1", ConsentSource.WEB, 9));
        consents.saveAndFlush(consent(r, c, "v2", ConsentSource.MOBILE, 10));
        assertThat(consents.findByCaseIdOrderByAcceptedAtAscIdAsc(c)).extracting(CaseConsent::getConsentVersion)
                .containsExactly("v1", "v3", "v2");
    }

    @Test
    void rejectsTheSameVersionTwice() {
        long r = user("consent-duplicate@example.com"), c = cases.save(aCase(r).build()).getId();
        consents.saveAndFlush(consent(r, c, "v1", ConsentSource.WEB, 9));
        assertThatThrownBy(() -> consents.saveAndFlush(consent(r, c, "v1", ConsentSource.API, 10)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reportsWhetherConsentWasGiven() {
        long r = user("consent-exists@example.com"), c = cases.save(aCase(r).build()).getId();
        assertThat(consents.existsByCaseIdAndConsentType(c, ConsentType.SENSITIVE_DATA_COLLECTION)).isFalse();
        consents.saveAndFlush(consent(r, c, "v1", ConsentSource.WEB, 9));
        assertThat(consents.existsByCaseIdAndConsentType(c, ConsentType.SENSITIVE_DATA_COLLECTION)).isTrue();
    }

    @ParameterizedTest @EnumSource(ConsentSource.class)
    void everyConsentSourceIsAcceptedByTheDatabase(ConsentSource source) {
        long r = user("consent-source@example.com"), c = cases.save(aCase(r).build()).getId();
        assertThat(consents.saveAndFlush(consent(r, c, "v1", source, 9)).getId()).isNotNull();
    }

    @ParameterizedTest @EnumSource(ConsentType.class)
    void everyConsentTypeIsAcceptedByTheDatabase(ConsentType type) {
        long r = user("consent-type@example.com"), c = cases.save(aCase(r).build()).getId();
        assertThat(consents.saveAndFlush(CaseConsent.builder().caseId(c).userId(r).consentType(type)
                .consentVersion("v1").source(ConsentSource.WEB).build()).getId()).isNotNull();
    }

    private CaseConsent consent(long r, long c, String version, ConsentSource source, int hour) {
        return CaseConsent.builder().caseId(c).userId(r).consentType(ConsentType.SENSITIVE_DATA_COLLECTION)
                .consentVersion(version).source(source).acceptedAt(at(hour)).build();
    }
}
