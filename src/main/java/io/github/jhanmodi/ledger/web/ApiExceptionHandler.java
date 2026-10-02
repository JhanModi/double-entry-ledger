package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.ledger.AccountNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 Problem Details responses (ADR-0017). The parent class already handles Spring MVC's
 * own errors (malformed JSON, failed validation, bad parameters) as 400s in the same format. This class adds the
 * domain errors, and a catch-all that never reveals internals.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The same answer whether the account is missing, another client's, or a system account (ADR-0017). */
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(
                HttpStatus.NOT_FOUND,
                "account-not-found",
                "Account not found",
                "Your client has no account with this id.");
    }

    /** Anything unexpected: logged in full on the server, but the client gets no details about the internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception while serving a request", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "Something went wrong on our side. It has been logged.");
    }

    static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
