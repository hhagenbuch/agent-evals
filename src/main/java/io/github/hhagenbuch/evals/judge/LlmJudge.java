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

/**
 * Scores a response 1–5 against free-text criteria using an LLM.
 * Requires {@code ANTHROPIC_API_KEY}; judge assertions are skipped when absent
 * so deterministic assertions can still gate CI without a key.
 */
public class LlmJudge {

    public record Verdict(int score, String rationale) {
    }

    private static final String MODEL = "claude-sonnet-5";

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public LlmJudge(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
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
    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in judge output: " + text);
        }
        return text.substring(start, end + 1);
    }
}
