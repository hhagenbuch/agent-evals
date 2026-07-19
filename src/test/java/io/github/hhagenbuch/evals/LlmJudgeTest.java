package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.judge.LlmJudge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJudgeTest {

    @Test
    void extractsJsonFromFencedOutput() {
        String text = "Here you go:\n```json\n{\"score\": 4, \"rationale\": \"clear\"}\n```";
        assertThat(LlmJudge.extractJson(text)).isEqualTo("{\"score\": 4, \"rationale\": \"clear\"}");
    }

    @Test
    void rejectsOutputWithoutJson() {
        assertThatThrownBy(() -> LlmJudge.extractJson("no json here"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void medianCombinesEnsembleScores() {
        assertThat(LlmJudge.median(List.of(4, 5, 4))).isEqualTo(4);   // odd: middle value
        assertThat(LlmJudge.median(List.of(2, 5, 3))).isEqualTo(3);   // odd, unsorted
        assertThat(LlmJudge.median(List.of(4, 5))).isEqualTo(5);      // even: rounded average (4.5 -> 5)
        assertThat(LlmJudge.median(List.of(3))).isEqualTo(3);         // single judge
    }

    @Test
    void unavailableWithoutKey() {
        assertThat(new LlmJudge(null).available()).isFalse();
        assertThat(new LlmJudge("").available()).isFalse();
        assertThat(new LlmJudge("sk-test").available()).isTrue();
    }

    @Test
    void snippetGivesAReadableErrorBodyForDiagnostics() {
        assertThat(LlmJudge.snippet(null)).isEqualTo("(empty body)");
        assertThat(LlmJudge.snippet("   ")).isEqualTo("(empty body)");
        // multi-line JSON error collapses to one line so it fits an exception message
        assertThat(LlmJudge.snippet("{\n \"error\": \"invalid x-api-key\"\n}"))
                .isEqualTo("{ \"error\": \"invalid x-api-key\" }");
        // long bodies are truncated
        assertThat(LlmJudge.snippet("x".repeat(500))).hasSize(203).endsWith("...");
    }
}
