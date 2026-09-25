package com.souldealers.crowdtracebackend.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {

    private final ClientAddressResolver resolver = new ClientAddressResolver();

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void theSocketAddressIsUsed() {
        assertThat(resolver.resolve(requestFrom("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void forwardedHeadersAreIgnoredEntirely() {
        MockHttpServletRequest request = requestFrom("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Real-IP", "198.51.100.2");
        request.addHeader("Forwarded", "for=198.51.100.3");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void equivalentIpv6FormsCanonicaliseToOneBucket() {
        String shortForm = resolver.resolve(requestFrom("::1"));
        String longForm = resolver.resolve(requestFrom("0:0:0:0:0:0:0:1"));

        assertThat(shortForm).isEqualTo(longForm);
    }

    @Test
    void distinctIpv6AddressesStayDistinct() {
        assertThat(resolver.resolve(requestFrom("2001:db8::1")))
                .isNotEqualTo(resolver.resolve(requestFrom("2001:db8::2")));
    }

    @Test
    void anUnparseableAddressFallsBackToItsRawForm() {
        assertThat(resolver.resolve(requestFrom("unknown"))).isEqualTo("unknown");
    }

    @Test
    void aMissingAddressYieldsAStablePlaceholder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }
}
