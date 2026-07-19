package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.assertions.Assertions;
import io.github.hhagenbuch.evals.model.AssertionSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionsTest {

    @Test
    void containsIsCaseInsensitive() {
        var spec = new AssertionSpec("contains", "Refund", null, null);
        assertThat(Assertions.evaluate(spec, "p", "we offer a full refund", null).passed()).isTrue();
        assertThat(Assertions.evaluate(spec, "p", "no returns", null).passed()).isFalse();
    }

    @Test
    void notContainsFlagsForbiddenText() {
        var spec = new AssertionSpec("not_contains", "error", null, null);
        assertThat(Assertions.evaluate(spec, "p", "all good", null).passed()).isTrue();
        assertThat(Assertions.evaluate(spec, "p", "ERROR: boom", null).passed()).isFalse();
    }

    @Test
    void regexMatches() {
        var spec = new AssertionSpec("regex", "\\d{3}-\\d{4}", null, null);
        assertThat(Assertions.evaluate(spec, "p", "call 555-1234", null).passed()).isTrue();
    }

    @Test
    void judgeSkipsWithoutKey() {
        var spec = new AssertionSpec("judge", null, "is polite", 4);
        var result = Assertions.evaluate(spec, "p", "hello", new io.github.hhagenbuch.evals.judge.LlmJudge(null));
        assertThat(result.skipped()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void unknownTypeFails() {
        var spec = new AssertionSpec("telepathy", null, null, null);
        assertThat(Assertions.evaluate(spec, "p", "x", null).passed()).isFalse();
    }
}
