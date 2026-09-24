package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.UNAUTHORIZED_MSG;

/**
 * Rejections raised inside the security filter chain never reach
 * GlobalExceptionHandler, which only sees exceptions thrown from controllers.
 * Without an explicit entry point Spring Security falls back to
 * Http403ForbiddenEntryPoint, turning every unauthenticated request into a 403
 * with an empty body. This preserves both the 401 and the ProblemDetail shape,
 * correlation ID included, so clients see one error contract across the API.
 */
@Component
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MSG);
        problem.setType(URI.create("urn:crowdtrace:problem:unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "UNAUTHORIZED");
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
