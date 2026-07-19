package io.github.hhagenbuch.evals.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls a chat endpoint shaped like
 * <a href="https://github.com/hhagenbuch/spring-ai-agent-starter">spring-ai-agent-starter</a>:
 * {@code POST {url} {"sessionId": "...", "message": prompt}} → {@code {"reply": "..."}}.
 */
public final class HttpTarget implements TargetSystem {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public HttpTarget(String url) {
        this.url = url;
    }

    @Override
    public String respond(String prompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("sessionId", "eval-" + System.nanoTime());
            body.put("message", prompt);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("target returned HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body()).path("reply").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling target", e);
        }
    }
}
