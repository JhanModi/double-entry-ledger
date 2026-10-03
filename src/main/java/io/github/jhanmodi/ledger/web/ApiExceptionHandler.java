package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.ScopeRequiredException;
import io.github.jhanmodi.ledger.ledger.AccountClosedException;
import io.github.jhanmodi.ledger.ledger.AccountNotFoundException;
import io.github.jhanmodi.ledger.ledger.InsufficientFundsException;
import io.github.jhanmodi.ledger.transfers.AmountTooLargeException;
import io.github.jhanmodi.ledger.transfers.DuplicateRequestException;
import io.github.jhanmodi.ledger.transfers.SameAccountException;
import io.github.jhanmodi.ledger.transfers.TransferAccountNotFoundException;
import io.github.jhanmodi.ledger.transfers.TransferNotFoundException;
import io.github.jhanmodi.ledger.transfers.WrongCurrencyException;
import io.github.jhanmodi.ledger.web.ApiJson.MoneyJson;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns exceptions into RFC 9457 Problem Details responses (ADR-0017). The parent class handles Spring MVC's own errors
 * (malformed requests, failed validation, bad parameters) as 400s in the same format; this class makes the 400s name
 * the fields at fault, adds one problem type per business error, and has a catch-all that never reveals internals.
 *
 * <p>Every problem carries a {@code requestId}, the same id as the {@code X-Request-Id} header (ADR-0021), so a client
 * can quote it and support can find the request's log lines.
 *
 * <p>Status codes: 400 for input that is malformed, 404 for something the client doesn't have, 409 for a request that
 * repeats one already done, and 422 for a well-formed request that the business rules refuse.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final URI INVALID_REQUEST_TYPE = URI.create("/problems/invalid-request");
    private static final String INVALID_REQUEST_TITLE = "Invalid request";

    // --- 404: not one of the client's own ---

    /** The same answer whether the account is missing, another client's, or a system account (ADR-0017). */
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "account-not-found",
                "Account not found",
                "Your client has no account with this id.");
    }

    /** As above, naming which of the two ids it was. That reveals nothing: the client chose both. */
    @ExceptionHandler(TransferAccountNotFoundException.class)
    ProblemDetail transferAccountNotFound(TransferAccountNotFoundException e, HttpServletRequest request) {
        String field = switch (e.side()) {
            case SOURCE -> "sourceAccountId";
            case DESTINATION -> "destinationAccountId";
        };
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "account-not-found",
                "Account not found",
                "Your client has no account with the " + field + " given.");
    }

    @ExceptionHandler(TransferNotFoundException.class)
    ProblemDetail transferNotFound(TransferNotFoundException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "transfer-not-found",
                "Transfer not found",
                "Your client has no transfer with this id.");
    }

    // --- 403: a check the security rules normally make first ---

    /** Only reachable if an endpoint lacks the right security rule; the service checked the scope anyway. */
    @ExceptionHandler(ScopeRequiredException.class)
    ProblemDetail scopeRequired(ScopeRequiredException e, HttpServletRequest request) {
        return problem(
                request, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This API key is not allowed to do that.");
    }

    // --- 409: a retry of something already done (ADR-0019) ---

    @ExceptionHandler(DuplicateRequestException.class)
    ProblemDetail duplicateRequest(DuplicateRequestException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.CONFLICT,
                "duplicate-request",
                "Duplicate request",
                "This Idempotency-Key was already used, so nothing was done this time. "
                        + "originalId identifies what the first request created.");
        problem.setProperty("originalId", e.originalId());
        return problem;
    }

    // --- 422: well-formed, but the business rules say no. Nothing was moved. ---

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "insufficient-funds",
                "Insufficient funds",
                "The source account's available balance is too low for this amount.");
    }

    @ExceptionHandler(WrongCurrencyException.class)
    ProblemDetail wrongCurrency(WrongCurrencyException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency-mismatch",
                "Currency mismatch",
                "The amount is in " + e.amountCurrency() + ", but the account is in " + e.accountCurrency()
                        + ". Money moves within one currency.");
    }

    @ExceptionHandler(SameAccountException.class)
    ProblemDetail sameAccount(SameAccountException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "same-account",
                "Same account",
                "The source and destination must be different accounts.");
    }

    @ExceptionHandler(AccountClosedException.class)
    ProblemDetail accountClosed(AccountClosedException e, HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "account-closed",
                "Account closed",
                "A closed account can't send or receive money.");
    }

    @ExceptionHandler(AmountTooLargeException.class)
    ProblemDetail amountTooLarge(AmountTooLargeException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "amount-too-large",
                "Amount too large",
                "One request may move at most the amount given in maximum, in minor units.");
        problem.setProperty("maximum", MoneyJson.of(e.maximum()));
        return problem;
    }

    // --- 400: malformed input, naming the fields at fault ---
    //
    // Every 400 is the same problem type, /problems/invalid-request, with an "errors" list of {field, message}. The
    // list names the fields, headers, or parameters at fault, and is empty only when no single one is (a body that
    // isn't JSON). Whether this API's validation or Spring's request handling caught it, the client sees one shape.

    /** A request body that parsed, but failed validation (e.g. a zero amount, or a missing field). */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> fields = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.add(InvalidField.of(error)));
        return invalidRequest(e, fields, headers, request);
    }

    /** Validation of headers, path variables, and query parameters, and of the body alongside them. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> fields = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(error -> fields.add(InvalidField.of(error)));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors()
                        .forEach(error -> fields.add(new InvalidField(name, error.getDefaultMessage())));
            }
        }
        return invalidRequest(e, fields, headers, request);
    }

    /**
     * A body that couldn't be read: not JSON, a field this API doesn't define, or a value of the wrong type, such as an
     * amount that isn't a JSON integer (ADR-0021). The parser's own message names Java classes, so it's never passed
     * on; only the JSON path of the field at fault is.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        JacksonException parse = jacksonCause(e);
        if (parse == null || parse.getPath().isEmpty()) {
            return invalidRequest(e, "The request body is missing or isn't valid JSON.", List.of(), headers, request);
        }
        String field = jsonPath(parse);
        String message = switch (parse) {
            case UnrecognizedPropertyException _ -> "is not a field this API accepts";
            // A number too big for its type, e.g. an amount above the largest long: never wrapped around.
            case InputCoercionException _ -> "is out of range";
            case InvalidFormatException invalid
            when invalid.getTargetType() != null && invalid.getTargetType().isEnum() ->
                "is not one of the accepted values";
            case MismatchedInputException mismatch
            when isWholeNumber(mismatch.getTargetType()) -> "must be a JSON integer";
            default -> "has the wrong type or format";
        };
        return invalidRequest(e, List.of(new InvalidField(field, message)), headers, request);
    }

    /** A required header that wasn't sent, such as {@code Idempotency-Key}. */
    @Override
    protected @Nullable ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (e instanceof MissingRequestHeaderException missing) {
            return invalidRequest(
                    e, List.of(new InvalidField(missing.getHeaderName(), "is required")), headers, request);
        }
        return invalidRequest(e, List.of(), headers, request);
    }

    /** A required query parameter that wasn't sent. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest(e, List.of(new InvalidField(e.getParameterName(), "is required")), headers, request);
    }

    /** A path variable or query parameter that isn't the right type, such as an id that isn't a UUID. */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = e instanceof MethodArgumentTypeMismatchException argument
                ? argument.getName()
                : String.valueOf(e.getPropertyName());
        return invalidRequest(e, List.of(new InvalidField(field, "has the wrong type or format")), headers, request);
    }

    // --- 500: anything unexpected ---

    /** Logged in full on the server; the client gets no details about the internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception while serving a request", e);
        return problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "Something went wrong on our side. It has been logged.");
    }

    /**
     * The last step for every problem the parent class creates. Adds the request id, as {@link #problem} does for this
     * class's own. And any 400 the overrides above don't cover (Spring has a few rarer ones) still becomes
     * {@code invalid-request} with an {@code errors} list, keeping Spring's own detail text.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (statusCode.value() == HttpStatus.BAD_REQUEST.value() && ABOUT_BLANK.equals(problem.getType())) {
                problem.setType(INVALID_REQUEST_TYPE);
                problem.setTitle(INVALID_REQUEST_TITLE);
                if (problem.getProperties() == null || !problem.getProperties().containsKey("errors")) {
                    problem.setProperty("errors", List.of());
                }
            }
            withRequestId(problem, request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // --- helpers ---

    /** One field at fault in a 400: its JSON path (e.g. {@code amount.amount}) or header name, and what's wrong. */
    record InvalidField(String field, @Nullable String message) {

        static InvalidField of(FieldError error) {
            return new InvalidField(error.getField(), error.getDefaultMessage());
        }
    }

    private @Nullable ResponseEntity<Object> invalidRequest(
            Exception e, List<InvalidField> fields, HttpHeaders headers, WebRequest request) {
        return invalidRequest(e, "Some of the request's fields are invalid; see errors.", fields, headers, request);
    }

    private @Nullable ResponseEntity<Object> invalidRequest(
            Exception e, String detail, List<InvalidField> fields, HttpHeaders headers, WebRequest request) {
        List<InvalidField> sorted = fields.stream()
                .sorted(Comparator.comparing(InvalidField::field))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(INVALID_REQUEST_TYPE);
        problem.setTitle(INVALID_REQUEST_TITLE);
        problem.setProperty("errors", sorted);
        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    static ProblemDetail problem(
            HttpServletRequest request, HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = problemDetail(status, type, title, detail);
        withRequestId(problem, request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }

    private static ProblemDetail problemDetail(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(title);
        return problem;
    }

    private static void withRequestId(ProblemDetail problem, @Nullable Object requestId) {
        if (requestId != null) {
            problem.setProperty("requestId", requestId.toString());
        }
    }

    /** The header or parameter name the client used, rather than the Java parameter's name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return header.name().isEmpty() ? header.value() : header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !(param.name().isEmpty() && param.value().isEmpty())) {
            return param.name().isEmpty() ? param.value() : param.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !(path.name().isEmpty() && path.value().isEmpty())) {
            return path.name().isEmpty() ? path.value() : path.name();
        }
        return String.valueOf(parameter.getParameterName());
    }

    private static @Nullable JacksonException jacksonCause(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                return jackson;
            }
        }
        return null;
    }

    /** The field's path in the JSON document, e.g. {@code amount.amount}, or {@code items[2].id} inside an array. */
    private static String jsonPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static boolean isWholeNumber(@Nullable Class<?> type) {
        return type == long.class
                || type == Long.class
                || type == int.class
                || type == Integer.class
                || type == short.class
                || type == Short.class;
    }
}
