package io.github.hhagenbuch.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One assertion from the dataset YAML. */
public record AssertionSpec(
        String type,
        String value,
        String criteria,
        @JsonProperty("min_score") Integer minScore) {
}
