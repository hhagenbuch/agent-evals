package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.judge.LlmJudge;
import io.github.hhagenbuch.evals.model.AssertionSpec;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;
import io.github.hhagenbuch.evals.model.EvalCase;
import io.github.hhagenbuch.evals.target.EchoTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void runsCasesAgainstTargetAndAggregates() {
        Dataset dataset = new Dataset("test", "echo", List.of(
                new EvalCase("passes", "ping", List.of(new AssertionSpec("contains", "ping", null, null))),
                new EvalCase("fails", "ping", List.of(new AssertionSpec("contains", "pong", null, null)))));

        List<CaseResult> results = EvalRunner.run(dataset, new EchoTarget(), new LlmJudge(null));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(1).passed()).isFalse();
    }

    @Test
    void thresholdGatePassesAtOrAboveMinPassRate() {
        assertThat(EvalRunner.meetsThreshold(9, 10, 0.9)).isTrue();   // exactly on the boundary
        assertThat(EvalRunner.meetsThreshold(8, 10, 0.9)).isFalse();  // just below
        assertThat(EvalRunner.meetsThreshold(10, 10, 1.0)).isTrue();  // default: all must pass
        assertThat(EvalRunner.meetsThreshold(9, 10, 1.0)).isFalse();
        assertThat(EvalRunner.meetsThreshold(0, 0, 1.0)).isTrue();    // empty dataset
    }
}
