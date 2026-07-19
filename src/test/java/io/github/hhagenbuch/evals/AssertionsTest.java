package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.assertions.Assertions;
import io.github.hhagenbuch.evals.model.AssertionSpec;
import io.github.hhagenbuch.evals.target.TargetResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionsTest {

    private static TargetResponse reply(String text) {
        return TargetResponse.of(text);
    }

    @Test
    void containsIsCaseInsensitive() {
        var spec = new AssertionSpec("contains", "Refund", null, null);
        assertThat(Assertions.evaluate(spec, "p", reply("we offer a full refund"), null).passed()).isTrue();
        assertThat(Assertions.evaluate(spec, "p", reply("no returns"), null).passed()).isFalse();
    }

    @Test
    void notContainsFlagsForbiddenText() {
        var spec = new AssertionSpec("not_contains", "error", null, null);
        assertThat(Assertions.evaluate(spec, "p", reply("all good"), null).passed()).isTrue();
        assertThat(Assertions.evaluate(spec, "p", reply("ERROR: boom"), null).passed()).isFalse();
    }

    @Test
    void regexMatches() {
        var spec = new AssertionSpec("regex", "\\d{3}-\\d{4}", null, null);
        assertThat(Assertions.evaluate(spec, "p", reply("call 555-1234"), null).passed()).isTrue();
    }

    @Test
    void judgeSkipsWithoutKey() {
        var spec = new AssertionSpec("judge", null, "is polite", 4);
        var result = Assertions.evaluate(spec, "p", reply("hello"), new io.github.hhagenbuch.evals.judge.LlmJudge(null));
        assertThat(result.skipped()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void toolCalledChecksTheTrajectory() {
        var spec = new AssertionSpec("tool_called", "calculator", null, null);
        assertThat(Assertions.evaluate(spec, "p",
                new TargetResponse("42", List.of("calculator")), null).passed()).isTrue();
        assertThat(Assertions.evaluate(spec, "p",
                new TargetResponse("42", List.of("clock")), null).passed()).isFalse();
        // no trajectory reported -> fails rather than silently passing
        assertThat(Assertions.evaluate(spec, "p", reply("42"), null).passed()).isFalse();
    }

    @Test
    void unknownTypeFails() {
        var spec = new AssertionSpec("telepathy", null, null, null);
        assertThat(Assertions.evaluate(spec, "p", reply("x"), null).passed()).isFalse();
    }
}
