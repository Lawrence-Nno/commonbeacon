package com.lawrencenno.commonbeacon.shared;

import java.util.LinkedHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(Exception.class)
    Object unexpected(Exception exception) {
        // Preserve framework statuses (404, 405, 415, etc.) without exposing their messages.
        if (exception instanceof org.springframework.web.ErrorResponse error && error.getStatusCode().is4xxClientError())
            return new org.springframework.http.ResponseEntity<>(
                    ApiProblems.problem(error.getStatusCode().value(), "INVALID_REQUEST", "The request could not be processed."),
                    error.getHeaders(), error.getStatusCode());
        OperationalLogs.failure(org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class),
                "http.unexpected_failure", exception, null);
        return ApiProblems.problem(500, "INTERNAL_ERROR", "The service could not complete the request.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        var problem = ApiProblems.problem(400, "VALIDATION_FAILED", "Check the highlighted fields.");
        var fields = new LinkedHashMap<String, String>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("fieldErrors", fields);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail malformed(Exception exception) {
        return ApiProblems.problem(400, "INVALID_REQUEST", "The request contains an invalid value.");
    }

    @ExceptionHandler(ApiFailure.class)
    ProblemDetail application(ApiFailure exception) {
        var problem = ApiProblems.problem(exception.status(), exception.code(), exception.getMessage());
        if (!exception.fieldErrors().isEmpty()) problem.setProperty("fieldErrors", exception.fieldErrors());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail stale(OptimisticLockingFailureException exception) {
        return ApiProblems.problem(409, "STALE_EDIT", "This item changed. Reload it before saving again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) {
        return ApiProblems.problem(409, "DATA_CONFLICT", "The change conflicts with existing data.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return ApiProblems.problem(403, "FORBIDDEN", "You do not have permission for this action.");
    }
}
