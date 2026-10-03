package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.ApiKeyVerifier;
import io.github.jhanmodi.ledger.clients.Scope;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Who may call what (ADR-0016, ADR-0017). Every endpoint needs its own rule; anything without one is denied.
 *
 * <p>Only when serving HTTP: in command-line mode ({@code clients create}) there's no web server, so there's nothing to
 * secure, and Spring Security's web support isn't there to build a filter chain from.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyVerifier verifier) throws Exception {
        SecurityProblemResponses problems = new SecurityProblemResponses();
        http
                // CSRF tricks a browser into sending credentials it attaches automatically, like cookies. API keys
                // travel in a header the caller sets explicitly, so there's nothing for CSRF to exploit, and no
                // session to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(verifier, problems), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        // Lets Spring Boot render its own error responses; they never include stack traces.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/accounts")
                        .hasAuthority(Authorities.of(Scope.WRITE))
                        .requestMatchers(HttpMethod.GET, "/v1/accounts/*", "/v1/accounts/*/entries")
                        .hasAuthority(Authorities.of(Scope.READ))
                        .requestMatchers(HttpMethod.POST, "/v1/transfers")
                        .hasAuthority(Authorities.of(Scope.WRITE))
                        .requestMatchers(HttpMethod.GET, "/v1/transfers/*")
                        .hasAuthority(Authorities.of(Scope.READ))
                        // Funding creates money the platform owes its customer, so it needs admin (ADR-0018).
                        .requestMatchers(HttpMethod.POST, "/v1/fundings")
                        .hasAuthority(Authorities.of(Scope.ADMIN))
                        // Deny by default: an endpoint added without a rule above is unreachable, even with a valid
                        // key.
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }
}
