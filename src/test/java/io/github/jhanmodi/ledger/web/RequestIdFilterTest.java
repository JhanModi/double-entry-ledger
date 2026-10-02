package io.github.jhanmodi.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.audit.RequestId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** How a request gets its id, and how the id stays confined to that one request (ADR-0021). */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest {

    private static final RequestId FIXED = new RequestId(UUID.fromString("0190a000-0000-7000-8000-000000000001"));

    private final RequestIdFilter filter = new RequestIdFilter(() -> FIXED);

    @Test
    void theIdIsInTheResponseHeaderTheRequestAndTheLoggingContextWhileTheRequestIsServed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();
        AtomicReference<Object> attribute = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            duringRequest.set(MDC.get(RequestIdFilter.MDC_KEY));
            attribute.set(req.getAttribute(RequestIdFilter.ATTRIBUTE));
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(FIXED.toString());
        assertThat(attribute.get()).isEqualTo(FIXED);
        assertThat(duringRequest.get()).isEqualTo(FIXED.toString());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                .as("the server thread serves other requests next; they mustn't inherit this id")
                .isNull();
    }

    @Test
    void anIdSentByTheClientIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/accounts");
        request.addHeader(RequestIdFilter.HEADER, "chosen-by-the-client");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(FIXED.toString());
    }

    @Test
    void theLoggingContextIsClearedEvenWhenTheRequestFails() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() ->
                        filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void eachRequestLeavesOneLogLineWithItsRouteTemplateNeverTheRawPath(CapturedOutput output) throws Exception {
        String accountId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/accounts/" + accountId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            // What Spring MVC records when it matches a controller method.
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/accounts/{accountId}");
            response.setStatus(404);
        });

        assertThat(output.getOut())
                .contains("GET /v1/accounts/{accountId} -> 404")
                .doesNotContain(accountId);
    }

    @Test
    void aRequestThatNeverReachesAControllerIsLoggedWithoutARoute(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/v1/transfers"), response, (req, res) -> {
            response.setStatus(401);
        });

        assertThat(output.getOut()).contains("POST (no route) -> 401").doesNotContain("/v1/transfers");
    }
}
