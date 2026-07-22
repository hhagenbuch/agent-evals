package io.github.hhagenbuch.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A golden test case: a prompt and the assertions its response must satisfy.
 *
 * <p>{@code required: true} marks a case the gate may never trade away: if it
 * fails, the run exits 1 regardless of how good the aggregate pass rate looks.
 * Regression cases exported from production incidents are the intended users —
 * an average must not be allowed to absorb the one case that pins a real
 * failure.
 */
public record EvalCase(
        String id,
        String prompt,
        @JsonProperty("assert") List<AssertionSpec> assertions,
        @JsonProperty("required") Boolean required) {

    public EvalCase(String id, String prompt, List<AssertionSpec> assertions) {
        this(id, prompt, assertions, null);
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
