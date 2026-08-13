package com.lucklotter.web;

import com.lucklotter.service.NotFoundException;
import com.lucklotter.support.Redact;
import com.lucklotter.service.ValidationException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into API responses.
 *
 * <p>Messages here are returned to the caller, so they say what is wrong with
 * the <em>request</em> and never echo stored data — a validation message that
 * quotes the offending value would put customer contact details into a response
 * body and, from there, into somebody's log (NFR-4).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Field-level bean validation failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onInvalidBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "Request validation failed");
        body.put("fieldErrors", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({ValidationException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> onBadRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException
                ? "Malformed request body"
                : e.getMessage();
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> onBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(body(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> onAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(HttpStatus.FORBIDDEN, "Forbidden"));
    }

    /**
     * Also covers a flag or customer that exists but belongs to someone else —
     * see {@link NotFoundException} for why that isn't a 403.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /**
     * Typically a POS transaction ID submitted twice concurrently: the first
     * request wins and the second hits the unique constraint. A sequential
     * replay never gets here — ingestion detects it and reports it as a
     * duplicate (NFR-3).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> onConflict(DataIntegrityViolationException e) {
        // Postgres quotes the offending key in the violation message, and that
        // key can be a customer's external reference (NFR-4).
        log.warn("Constraint violation on write: cause={}", Redact.scrubStackTrace(e));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "Conflicts with an existing record"));
    }

    /**
     * A URL that matches no route. Spring raises this from the static-resource
     * handler, so without an explicit case it falls through to the catch-all
     * below and a mistyped path answers 500 — telling the caller the server
     * broke when the request was simply wrong, and putting routine 404s into
     * the error log where a real fault becomes harder to see.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNoRoute(NoResourceFoundException e) {
        log.debug("No route for request: path={}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, "No such endpoint"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        // Detail goes to the log, correlated by request ID; the caller gets none
        // of it, since stack traces and driver messages leak internals. Scrubbed
        // on the way in: a malformed-payload error quotes the payload, and an
        // ingest payload carries contact details (NFR-4).
        log.error("Unhandled exception: cause={}", Redact.scrubStackTrace(e));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"));
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
