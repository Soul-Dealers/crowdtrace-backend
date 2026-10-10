package com.souldealers.crowdtracebackend.shared.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearsCorrelationId() {
        MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
    }

    @Test
    void acceptsAValid64CharacterHeaderAndKeepsItInMdcForTheChain() throws Exception {
        String header = "Aa09._-" + "x".repeat(57);
        var request = requestWith(header);
        var response = new MockHttpServletResponse();
        var mdcInsideChain = new AtomicReference<String>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                mdcInsideChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)));

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(header);
        assertThat(mdcInsideChain.get()).isEqualTo(header);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesMissingAndMalformedHeadersWithTheSameUuidInMdcAndResponse() throws Exception {
        String[] headers = {null, "", "   ", "x".repeat(65), "has spaces", "<bad>", "line\nbreak", "café"};

        for (String header : headers) {
            var request = requestWith(header);
            var response = new MockHttpServletResponse();
            var mdcInsideChain = new AtomicReference<String>();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    mdcInsideChain.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)));

            String responseId = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
            assertThat(responseId).as("response for header %s", header).matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(mdcInsideChain.get()).isEqualTo(responseId);
            assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
        }
    }

    @Test
    void removesMdcValueWhenTheFilterChainThrows() {
        var request = requestWith("chain-failure");
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isEqualTo("chain-failure");
            throw new ServletException("chain failed");
        })).isInstanceOf(ServletException.class).hasMessage("chain failed");

        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    private static MockHttpServletRequest requestWith(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        return request;
    }
}
