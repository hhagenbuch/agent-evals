package io.github.hhagenbuch.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** A golden test case: a prompt and the assertions its response must satisfy. */
public record EvalCase(
        String id,
        String prompt,
        @JsonProperty("assert") List<AssertionSpec> assertions) {
}
