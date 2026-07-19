package io.github.hhagenbuch.evals.assertions;

import io.github.hhagenbuch.evals.judge.LlmJudge;
import io.github.hhagenbuch.evals.model.AssertionResult;
import io.github.hhagenbuch.evals.model.AssertionSpec;
import io.github.hhagenbuch.evals.target.TargetResponse;

import java.util.Locale;
import java.util.regex.Pattern;

/** Evaluates one {@link AssertionSpec} against a response. */
public final class Assertions {

    private Assertions() {
    }

    public static AssertionResult evaluate(AssertionSpec spec, String prompt, TargetResponse response, LlmJudge judge) {
        String reply = response.reply();
        return switch (spec.type().toLowerCase(Locale.ROOT)) {
            case "contains" -> contains(spec, reply);
            case "not_contains" -> notContains(spec, reply);
            case "regex" -> regex(spec, reply);
            case "judge" -> judged(spec, prompt, reply, judge);
            case "tool_called" -> toolCalled(spec, response);
            default -> AssertionResult.fail("unknown assertion type '" + spec.type() + "'",
                    "supported: contains, not_contains, regex, judge, tool_called");
        };
    }

    /** Trajectory assertion: did the agent actually invoke the named tool? */
    private static AssertionResult toolCalled(AssertionSpec spec, TargetResponse response) {
        String description = "called tool \"" + spec.value() + "\"";
        if (response.toolsUsed().isEmpty()) {
            return AssertionResult.fail(description,
                    "target reported no tool trace (does its response include toolsUsed?)");
        }
        return response.toolsUsed().contains(spec.value())
                ? AssertionResult.pass(description)
                : AssertionResult.fail(description, "tools used: " + response.toolsUsed());
    }

    private static AssertionResult contains(AssertionSpec spec, String response) {
        String description = "contains \"" + spec.value() + "\"";
        return response.toLowerCase(Locale.ROOT).contains(spec.value().toLowerCase(Locale.ROOT))
                ? AssertionResult.pass(description)
                : AssertionResult.fail(description, "not found in response");
    }

    private static AssertionResult notContains(AssertionSpec spec, String response) {
        String description = "does not contain \"" + spec.value() + "\"";
        return response.toLowerCase(Locale.ROOT).contains(spec.value().toLowerCase(Locale.ROOT))
                ? AssertionResult.fail(description, "found in response")
                : AssertionResult.pass(description);
    }

    private static AssertionResult regex(AssertionSpec spec, String response) {
        String description = "matches /" + spec.value() + "/";
        return Pattern.compile(spec.value(), Pattern.DOTALL).matcher(response).find()
                ? AssertionResult.pass(description)
                : AssertionResult.fail(description, "pattern not found");
    }

    private static AssertionResult judged(AssertionSpec spec, String prompt, String response, LlmJudge judge) {
        int minScore = spec.minScore() != null ? spec.minScore() : 4;
        String description = "judge ≥" + minScore + ": " + spec.criteria();
        if (judge == null || !judge.available()) {
            return AssertionResult.skip(description, "no ANTHROPIC_API_KEY — judge skipped");
        }
        LlmJudge.Verdict verdict = judge.judge(prompt, response, spec.criteria());
        return verdict.score() >= minScore
                ? AssertionResult.pass(description + " (scored " + verdict.score() + ")")
                : AssertionResult.fail(description,
                        "scored " + verdict.score() + ": " + verdict.rationale());
    }
}
