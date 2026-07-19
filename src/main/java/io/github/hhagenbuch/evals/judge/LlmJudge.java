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

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public LlmJudge(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Polls the judge {@link #DEFAULT_ENSEMBLE} times and returns the median
     * score, reducing the variance of any single stochastic grade.
     */
    public Verdict judgeEnsemble(String prompt, String response, String criteria) {
        return judgeEnsemble(prompt, response, criteria, DEFAULT_ENSEMBLE);
    }

    public Verdict judgeEnsemble(String prompt, String response, String criteria, int k) {
        List<Integer> scores = new ArrayList<>();
        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Verdict v = judge(prompt, response, criteria);
            verdicts.add(v);
            scores.add(v.score());
        }
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
            HttpResponse<String> response2 = client.send(request, HttpResponse.BodyHandlers.ofString());
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
