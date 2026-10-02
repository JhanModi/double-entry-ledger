package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.ApiKeyVerifier;
import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.clients.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry {@code Authorization: Bearer <api key>} (ADR-0016).
 *
 * <ul>
 *   <li>No Authorization header: the request carries on unauthenticated, and the authorization rules answer 401 for
 *       anything that needs a key.
 *   <li>A header with an invalid key, or a scheme other than Bearer: rejected right here with 401. A bad credential
 *       never quietly becomes an anonymous request.
 *   <li>A valid key: the request runs as that client, with one authority per scope ({@code SCOPE_read}, ...).
 * </ul>
 *
 * <p>Not a Spring bean on purpose: Spring Boot registers every {@code Filter} bean with the servlet container, so as a
 * bean it would also run a second time outside the security chain.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyVerifier verifier;
    private final AuthenticationEntryPoint unauthorized;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    ApiKeyAuthenticationFilter(ApiKeyVerifier verifier, AuthenticationEntryPoint unauthorized) {
        this.verifier = verifier;
        this.unauthorized = unauthorized;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AuthenticatedClient> client = header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? verifier.verify(header.substring(BEARER.length()).trim())
                : Optional.empty();
        if (client.isEmpty()) {
            contexts.clearContext();
            unauthorized.commence(request, response, new BadCredentialsException("invalid API key"));
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                client.get(), null, authorities(client.get().scopes())));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }

    private static List<SimpleGrantedAuthority> authorities(Set<Scope> scopes) {
        return scopes.stream()
                .map(scope -> new SimpleGrantedAuthority(Authorities.of(scope)))
                .toList();
    }
}
