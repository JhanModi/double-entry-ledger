package io.github.jhanmodi.ledger.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The 401 and 403 responses, in the same Problem Details format as every other error (RFC 9457, ADR-0017). They're
 * fixed text on purpose: whatever went wrong, the response says only "you need a valid key" or "your key can't do
 * this", never which check failed.
 */
final class SecurityProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHORIZED = """
            {"type":"/problems/unauthorized","title":"Unauthorized","status":401,\
            "detail":"A valid API key is required: send it as 'Authorization: Bearer <key>'."}""";

    private static final String FORBIDDEN = """
            {"type":"/problems/forbidden","title":"Forbidden","status":403,\
            "detail":"This API key is not allowed to do that."}""";

    /** 401: no key, or a key that isn't valid. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        // RFC 6750: a 401 says which authentication scheme the client should use.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
    }

    /** 403: a valid key whose scopes don't cover this request. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN);
    }

    private static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
