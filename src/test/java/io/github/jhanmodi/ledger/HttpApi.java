package io.github.jhanmodi.ledger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP calls to the application under test, for API tests. Each call can carry any Authorization header. */
public final class HttpApi {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public HttpApi(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }

    public Response get(String path, String authorization) {
        return send("GET", path, authorization, null);
    }

    public Response post(String path, String authorization, String jsonBody) {
        return send("POST", path, authorization, jsonBody);
    }

    public Response send(String method, String path, String authorization, String jsonBody) {
        return send(method, path, authorization, jsonBody, Map.of());
    }

    /** As {@link #send(String, String, String, String)}, plus any other request headers. */
    public Response send(
            String method, String path, String authorization, String jsonBody, Map<String, String> otherHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(
                        method,
                        jsonBody == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        otherHeaders.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Content-Type"),
                    response.headers().firstValue("Location"),
                    response.headers().firstValue("WWW-Authenticate"),
                    response.headers().firstValue("X-Request-Id"),
                    response.headers().firstValue("Retry-After"),
                    response.headers().firstValue("Idempotent-Replayed"));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, e);
        }
    }

    public record Response(
            int status,
            String body,
            Optional<String> contentType,
            Optional<String> location,
            Optional<String> wwwAuthenticate,
            Optional<String> requestId,
            Optional<String> retryAfter,
            Optional<String> idempotentReplayed) {

        public JsonNode json() {
            return JsonMapper.shared().readTree(body);
        }
    }
}
