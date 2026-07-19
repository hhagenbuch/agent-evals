package io.github.hhagenbuch.evals.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Scores a response 1–5 against free-text criteria using an LLM.
 * Requires {@code ANTHROPIC_API_KEY}; judge assertions are skipped when absent
 * so deterministic assertions can still gate CI without a key.
 */
public class LlmJudge {

    public record Verdict(int score, String rationale) {
    }

    private static final String MODEL = "claude-sonnet-5";

    /** Default ensemble size: the judge is polled this many times and the scores are combined by median. */
    public static final int DEFAULT_ENSEMBLE = 3;

    /** Retry knobs: parallel cases × concurrent ensemble burst judge calls, so a 429/5xx blip must not fail a case. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MILLIS = 500;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final int ensembleSize;

    public LlmJudge(String apiKey) {
        this(apiKey, DEFAULT_ENSEMBLE);
    }

    /**
     * @param ensembleSize how many times each judge assertion polls the model
     *                     (combined by median). {@code 1} disables ensembling —
     *                     one call per assertion, a third of the token cost.
     */
    public LlmJudge(String apiKey, int ensembleSize) {
        this.apiKey = apiKey;
        this.ensembleSize = Math.max(1, ensembleSize);
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Polls the judge {@code ensembleSize} times and returns the median score,
     * reducing the variance of any single stochastic grade.
     */
    public Verdict judgeEnsemble(String prompt, String response, String criteria) {
        return judgeEnsemble(prompt, response, criteria, ensembleSize);
    }

    /**
     * Runs {@code k} judge calls concurrently (each case already runs on its own
     * virtual thread, so k more is cheap) and combines their scores by median.
     * {@code k == 1} short-circuits to a single call — no threads, no median.
     */
    public Verdict judgeEnsemble(String prompt, String response, String criteria, int k) {
        if (k <= 1) {
            return judge(prompt, response, criteria);
        }
        List<Verdict> verdicts = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Verdict>> futures = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                futures.add(executor.submit(() -> judge(prompt, response, criteria)));
            }
            for (Future<Verdict> future : futures) {
                verdicts.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while judging", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("judge call failed", cause);
        }
        List<Integer> scores = verdicts.stream().map(Verdict::score).toList();
        int median = median(scores);
        String rationale = verdicts.stream()
                .filter(v -> v.score() == median)
                .findFirst()
                .orElse(verdicts.get(0))
                .rationale();
        return new Verdict(median, "median of " + scores + ": " + rationale);
    }

    /** Median of the scores; even counts average the two middle values (rounded). */
    public static int median(List<Integer> scores) {
        List<Integer> sorted = scores.stream().sorted().toList();
        int n = sorted.size();
        int mid = n / 2;
        return n % 2 == 1
                ? sorted.get(mid)
                : (int) Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    public Verdict judge(String prompt, String response, String criteria) {
        String judgePrompt = """
                You are grading an AI assistant's response.

                <user_prompt>%s</user_prompt>
                <assistant_response>%s</assistant_response>
                <criteria>%s</criteria>

                Score how well the response satisfies the criteria from 1 (fails badly)
                to 5 (fully satisfies). Reply with ONLY a JSON object:
                {"score": <1-5>, "rationale": "<one sentence>"}
                """.formatted(prompt, response, criteria);
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("max_tokens", 256);
            ArrayNode messages = body.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", judgePrompt);

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response2 = sendWithRetry(request);
            String text = mapper.readTree(response2.body())
                    .path("content").path(0).path("text").asText();
            JsonNode verdict = mapper.readTree(extractJson(text));
            return new Verdict(verdict.path("score").asInt(), verdict.path("rationale").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while judging", e);
        }
    }

    /**
     * Sends the judge request with a small exponential backoff on retryable
     * failures (HTTP 429/5xx and transient {@link IOException}s), mirroring the
     * starter's {@code AnthropicClient}. Ensembling multiplies the request rate,
     * so without this a single rate-limit blip would redden an otherwise-green case.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status != 429 && status < 500) {
                    return response; // success, or a non-retryable client error the caller will surface
                }
                last = new IOException("judge API returned HTTP " + status);
            } catch (IOException e) {
                last = e; // connection reset / read timeout — retry
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(BACKOFF_BASE_MILLIS * (1L << (attempt - 1))); // 500ms, 1s, ...
            }
        }
        throw last;
    }

    /** Tolerates judges that wrap JSON in prose or code fences. */
    public static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in judge output: " + text);
        }
        return text.substring(start, end + 1);
    }
}
