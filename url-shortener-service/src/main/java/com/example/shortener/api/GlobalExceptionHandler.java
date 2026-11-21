package com.example.shortener.api;

import com.example.shortener.idempotency.IdempotencyConflictException;
import com.example.shortener.service.AliasTakenException;
import com.example.shortener.service.BadRequestException;
import com.example.shortener.service.LinkGoneException;
import com.example.shortener.service.LinkNotFoundException;
import com.example.shortener.validation.InvalidUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Maps domain exceptions to RFC 7807 problem details. Framework errors (bean validation, malformed JSON,
 * missing params) are handled by {@link ResponseEntityExceptionHandler} in the same format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://errors.shortener.example/";

    @ExceptionHandler(InvalidUrlException.class)
    ProblemDetail invalidUrl(InvalidUrlException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-url", "Invalid URL", e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad Request", e.getMessage());
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail notFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not Found", "No link with code '" + e.getShortCode() + "'");
    }

    @ExceptionHandler(LinkGoneException.class)
    ProblemDetail gone(LinkGoneException e) {
        return problem(HttpStatus.GONE, "gone", "Gone", "Link '" + e.getShortCode() + "' is no longer active");
    }

    @ExceptionHandler(AliasTakenException.class)
    ProblemDetail aliasTaken(AliasTakenException e) {
        return problem(HttpStatus.CONFLICT, "alias-taken", "Alias Taken",
                "Alias '" + e.getShortCode() + "' is already in use");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused", "Idempotency Key Reused",
                e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", "The request conflicts with existing data");
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug));
        pd.setTitle(title);
        return pd;
    }
}
