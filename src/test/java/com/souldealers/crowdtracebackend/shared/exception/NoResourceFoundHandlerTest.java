package com.souldealers.crowdtracebackend.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class NoResourceFoundHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void anUnmappedRouteIsA404NotA500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/cases/42");

        ProblemDetail body = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/v1/admin/cases/42", "api/v1/admin/cases/42"),
                request);

        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getProperties()).containsEntry("code", "RESOURCE_NOT_FOUND");
        assertThat(body.getDetail()).doesNotContain("static resource");
    }
}
