package io.github.hhagenbuch.evals.model;

import java.util.List;

public record CaseResult(String caseId, String response, List<AssertionResult> assertions, long millis) {

    public boolean passed() {
        return assertions.stream().allMatch(AssertionResult::passed);
    }
}
