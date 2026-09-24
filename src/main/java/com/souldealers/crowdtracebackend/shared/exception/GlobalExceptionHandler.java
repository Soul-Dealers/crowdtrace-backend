package com.souldealers.crowdtracebackend.shared.exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;

import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.UnauthorizedException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.FieldError;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.*;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({NoSuchElementException.class, NotFoundException.class})
    public ProblemDetail handleResourceNotFound(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                exception.getMessage(),
                "RESOURCE_NOT_FOUND",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                exception.getMessage(),
                "INVALID_REQUEST",
                request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflictException(
            IllegalStateException exception,
            HttpServletRequest request){

        return problem(
                HttpStatus.CONFLICT,
                "Conflict",
                exception.getMessage(),
                "CONFLICT",
                request
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "Conflict",
                exception.getMessage(),
                "CONFLICT",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                VALIDATION_FAILED_MSG,
                "VALIDATION_FAILED",
                request
        );

        Map<String, List<String>> errors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            String message = fieldError.getDefaultMessage() == null
                    ? "Invalid value"
                    : fieldError.getDefaultMessage();

            errors.computeIfAbsent(fieldError.getField(), ignored -> new ArrayList<>())
                    .add(message);
        }

        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(
            ValidationException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                exception.getMessage(),
                "VALIDATION_FAILED",
                request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException exception, HttpServletRequest request) {
        log.warn("Unauthorized access attempt while processing {} {}",
                request.getMethod(), request.getRequestURI());
        return problem(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                exception.getMessage(),
                "UNAUTHORIZED",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request) {
        log.warn("Authentication failed while processing {} {}",
                request.getMethod(), request.getRequestURI());
        return problem(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                UNAUTHORIZED_MSG,
                "UNAUTHORIZED",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleForbidden(AccessDeniedException exception, HttpServletRequest request) {
        log.warn("Forbidden access attempt to {} {}", request.getMethod(), request.getRequestURI());
        return problem(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                UNAUTHORIZED_MSG,
                "FORBIDDEN",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected error while processing {} {} (exceptionType={})",
                request.getMethod(), request.getRequestURI(), exception.getClass().getName());

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                INTERNAL_SERVER_ERROR_MSG,
                "INTERNAL_ERROR",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String code,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);

        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        problem.setType(URI.create("urn:crowdtrace:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);

        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);

        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
