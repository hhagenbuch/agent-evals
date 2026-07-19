package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.judge.LlmJudge;
import org.junit.jupiter.api.Test;

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
    void unavailableWithoutKey() {
        assertThat(new LlmJudge(null).available()).isFalse();
        assertThat(new LlmJudge("").available()).isFalse();
        assertThat(new LlmJudge("sk-test").available()).isTrue();
    }
}
