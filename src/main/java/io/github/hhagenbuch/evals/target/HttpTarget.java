package io.github.hhagenbuch.evals.target;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls a chat endpoint shaped like
 * <a href="https://github.com/hhagenbuch/spring-ai-agent-starter">spring-ai-agent-starter</a>:
 * {@code POST {url} {"sessionId": "...", "message": prompt}} → {@code {"reply": "..."}}.
 *
 * <p>When the gate runs against a freshly-created canary (agent-operator), the
 * eval Job can beat the target's startup: the JVM is still booting and the port
 * is not yet accepting connections. That is a race, not a verdict — so a
 * connection-level failure is retried with backoff for up to
 * {@link #READINESS_BUDGET} before it is allowed to fail the case. A 200 with a
 * bad answer is a real failure and is never retried; only "the target isn't up
 * yet" is.
 */
public final class HttpTarget implements TargetSystem {

    /** How long to keep retrying a target that isn't accepting requests yet. */
    static final Duration READINESS_BUDGET = Duration.ofSeconds(90);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final Sleeper sleeper;
    private final Duration readinessBudget;

    public HttpTarget(String url) {
        this(url, READINESS_BUDGET, Thread::sleep);
    }

    HttpTarget(String url, Duration readinessBudget, Sleeper sleeper) {
        this.url = url;
        this.readinessBudget = readinessBudget;
        this.sleeper = sleeper;
    }

    /** Seam so tests can drive the backoff without real clock time. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public TargetResponse respond(String prompt) {
        long deadline = System.nanoTime() + readinessBudget.toNanos();
        long backoff = FIRST_BACKOFF.toMillis();
        RuntimeException last = null;
        while (true) {
            try {
                return call(prompt);
            } catch (TargetNotReadyException notReady) {
                last = notReady;
                if (System.nanoTime() >= deadline) {
                    throw new UncheckedIOException("target never became ready within " + readinessBudget,
                            (IOException) notReady.getCause());
                }
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the target", e);
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF.toMillis());
            }
        }
    }

    private TargetResponse call(String prompt) {
        HttpResponse<String> response;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("sessionId", "eval-" + System.nanoTime());
            body.put("message", prompt);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Connection refused / reset / timeout: the target isn't up yet — retryable.
            throw new TargetNotReadyException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling target", e);
        }
        // 5xx is "up but not serving yet" (canary rolling); 4xx/other are real.
        if (response.statusCode() >= 500) {
            throw new TargetNotReadyException(new IOException("target returned HTTP " + response.statusCode()));
        }
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("target returned HTTP " + response.statusCode()));
        }
        try {
            JsonNode json = mapper.readTree(response.body());
            List<String> toolsUsed = new ArrayList<>();
            for (JsonNode tool : json.path("toolsUsed")) {
                toolsUsed.add(tool.asText());
            }
            return new TargetResponse(json.path("reply").asText(), toolsUsed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A connection-level failure that means "not ready yet", distinct from a bad answer. */
    private static final class TargetNotReadyException extends RuntimeException {
        TargetNotReadyException(IOException cause) {
            super(cause);
        }
    }
}
