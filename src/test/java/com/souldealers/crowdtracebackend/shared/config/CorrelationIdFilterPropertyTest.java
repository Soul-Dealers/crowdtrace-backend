package com.souldealers.crowdtracebackend.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Whatever a client sends, only a well-formed id reaches the response, logs and audit rows (CT-020, 4cfc986e). */
@Tag("fast")
class CorrelationIdFilterPropertyTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Property
    void theIdThatLeavesIsAlwaysWellFormed(@ForAll("headers") String header) throws Exception {
        assertThat(correlationIdFor(header)).matches(AuditRecorder.CORRELATION_ID_PATTERN);
    }

    @Property
    void aWellFormedIdIsKeptAsIs(@ForAll("validIds") String header) throws Exception {
        assertThat(correlationIdFor(header)).isEqualTo(header);
    }

    @Provide
    Arbitrary<String> headers() {
        return Arbitraries.oneOf(validIds(), Arbitraries.strings().ofMaxLength(100), Arbitraries.just(null));
    }

    @Provide
    Arbitrary<String> validIds() {
        return Arbitraries.strings().withChars("abcXYZ019._-").ofMinLength(1).ofMaxLength(64);
    }

    private String correlationIdFor(String header) throws Exception {
        var request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, header);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
    }
}
