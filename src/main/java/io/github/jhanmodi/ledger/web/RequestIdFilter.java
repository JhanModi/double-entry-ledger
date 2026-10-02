package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.audit.RequestId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives every request a server-generated id (ADR-0021), before anything else runs, including authentication.
 *
 * <ul>
 *   <li>It's put in the logging context (MDC) as {@code requestId}, so every log line written while serving the request
 *       carries it. The log pattern in application.yml prints it.
 *   <li>It's returned in the {@code X-Request-Id} header, on every response, 401s included.
 *   <li>It's stored as a request attribute, for the code that audits the request ({@link Callers}).
 * </ul>
 *
 * An id sent by the client is ignored: request ids come only from the server, so they're unique and can't be forged.
 *
 * <p>Every request also leaves one log line when it finishes: method, route, and status. That line is how rejected
 * requests, such as 401s, show up in the logs (ADR-0020 audits only committed actions). It names the route template
 * ({@code /v1/accounts/{accountId}}), never the raw path, so ids and query strings stay out of the logs.
 */
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String ATTRIBUTE = RequestId.class.getName();
    static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private final Supplier<RequestId> requestIds;

    RequestIdFilter(Supplier<RequestId> requestIds) {
        this.requestIds = requestIds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestId id = requestIds.get();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id.toString());
        MDC.put(MDC_KEY, id.toString());
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            log.info(
                    "{} {} -> {}",
                    request.getMethod(),
                    route(request),
                    completed ? String.valueOf(response.getStatus()) : "failed");
            // The thread goes back to the server's pool and will serve other requests: they mustn't inherit this id.
            MDC.remove(MDC_KEY);
        }
    }

    /** The matched route template, or a placeholder if the request never reached a controller (e.g. a 401). */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "(no route)" : pattern.toString();
    }
}
