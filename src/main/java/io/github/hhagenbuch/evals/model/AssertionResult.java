package io.github.hhagenbuch.evals.model;

public record AssertionResult(String description, boolean passed, boolean skipped, String detail) {

    public static AssertionResult pass(String description) {
        return new AssertionResult(description, true, false, "");
    }

    public static AssertionResult fail(String description, String detail) {
        return new AssertionResult(description, false, false, detail);
    }

    public static AssertionResult skip(String description, String reason) {
        return new AssertionResult(description, true, true, reason);
    }
}
