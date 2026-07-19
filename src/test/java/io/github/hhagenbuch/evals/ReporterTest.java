package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.model.AssertionResult;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;
import io.github.hhagenbuch.evals.report.Reporter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReporterTest {

    @Test
    void escapesPipesAndNewlinesInRationalesSoTheTableSurvives() {
        AssertionResult chatty = AssertionResult.fail(
                "judge ≥4: quality",
                "scored 2: the reply says a | b\nand then rambles onto a new line");
        CaseResult result = new CaseResult("c1", "reply", List.of(chatty), 42);

        String md = Reporter.markdown(new Dataset("d", "echo", List.of()), List.of(result));

        // the literal pipe is escaped so it can't open a phantom column
        assertThat(md).contains("a \\| b");
        // the newline is collapsed to a space, so the rationale stays on one row
        assertThat(md).contains("a \\| b and then rambles onto a new line");
        // and the raw newline is gone from the rendered detail
        assertThat(md).doesNotContain("b\nand then");
    }
}
