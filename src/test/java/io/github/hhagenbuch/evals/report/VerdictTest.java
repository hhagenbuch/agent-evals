package io.github.hhagenbuch.evals.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.evals.model.AssertionResult;
import io.github.hhagenbuch.evals.model.AssertionSpec;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;
import io.github.hhagenbuch.evals.model.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictTest {

    private static final List<AssertionSpec> ANY = List.of(new AssertionSpec("contains", "x", null, null));

    private static CaseResult pass(String id) {
        return new CaseResult(id, "ok", List.of(AssertionResult.pass("contains 'x'")), 1);
    }

    private static CaseResult fail(String id) {
        return new CaseResult(id, "nope", List.of(AssertionResult.fail("contains 'x'", "missing")), 1);
    }

    @Test
    void aRequiredCaseFailingFailsTheGateEvenAtAPassingAggregate() {
        // 9/10 pass; threshold 0.8 comfortably met — but the one failure is required.
        Dataset dataset = new Dataset("suite", "echo", List.of(
                new EvalCase("incident", "p", ANY, true),
                new EvalCase("a", "p", ANY), new EvalCase("b", "p", ANY),
                new EvalCase("c", "p", ANY), new EvalCase("d", "p", ANY),
                new EvalCase("e", "p", ANY), new EvalCase("f", "p", ANY),
                new EvalCase("g", "p", ANY), new EvalCase("h", "p", ANY),
                new EvalCase("i", "p", ANY)));
        List<CaseResult> results = List.of(fail("incident"),
                pass("a"), pass("b"), pass("c"), pass("d"), pass("e"),
                pass("f"), pass("g"), pass("h"), pass("i"));

        Verdict verdict = Verdict.of(dataset, results, 0.8, Set.of());

        assertThat(verdict.aggregatePassed()).isTrue();     // the average looks fine...
        assertThat(verdict.requiredFailed()).containsExactly("incident");
        assertThat(verdict.gatePassed()).isFalse();         // ...and the gate still fails
    }

    @Test
    void requireFlagForcesACaseRequiredAtRuntime() {
        Dataset dataset = new Dataset("suite", "echo", List.of(
                new EvalCase("x", "p", ANY), new EvalCase("y", "p", ANY)));

        Verdict verdict = Verdict.of(dataset, List.of(fail("x"), pass("y")), 0.5, Set.of("x"));

        assertThat(verdict.aggregatePassed()).isTrue();
        assertThat(verdict.gatePassed()).isFalse();
        assertThat(verdict.cases().get(0).required()).isTrue();
    }

    @Test
    void allRequiredPassingGatesOnTheAggregateAlone() {
        Dataset dataset = new Dataset("suite", "echo", List.of(
                new EvalCase("incident", "p", ANY, true), new EvalCase("y", "p", ANY)));

        Verdict verdict = Verdict.of(dataset, List.of(pass("incident"), fail("y")), 1.0, Set.of());

        assertThat(verdict.requiredFailed()).isEmpty();
        assertThat(verdict.aggregatePassed()).isFalse();  // 0.5 < 1.0
        assertThat(verdict.gatePassed()).isFalse();
    }

    @Test
    void verdictJsonCarriesTheDocumentedSchema() throws Exception {
        Dataset dataset = new Dataset("suite", "echo", List.of(
                new EvalCase("incident", "p", ANY, true), new EvalCase("y", "p", ANY)));
        String json = Verdict.of(dataset, List.of(fail("incident"), pass("y")), 0.9, Set.of()).toJson();

        JsonNode doc = new ObjectMapper().readTree(json);
        assertThat(doc.path("schema").asText()).isEqualTo("agent-evals/verdict/v1");
        assertThat(doc.path("dataset").asText()).isEqualTo("suite");
        assertThat(doc.path("total").asInt()).isEqualTo(2);
        assertThat(doc.path("passed").asLong()).isEqualTo(1);
        assertThat(doc.path("passRate").asDouble()).isEqualTo(0.5);
        assertThat(doc.path("minPassRate").asDouble()).isEqualTo(0.9);
        assertThat(doc.path("aggregatePassed").isBoolean()).isTrue();
        assertThat(doc.path("gatePassed").asBoolean()).isFalse();
        assertThat(doc.path("requiredFailed").get(0).asText()).isEqualTo("incident");
        JsonNode c0 = doc.path("cases").get(0);
        assertThat(c0.path("id").asText()).isEqualTo("incident");
        assertThat(c0.path("passed").asBoolean()).isFalse();
        assertThat(c0.path("required").asBoolean()).isTrue();
        assertThat(c0.path("millis").isNumber()).isTrue();
    }

    @Test
    void verdictRoundTripsThroughJson() {
        Dataset dataset = new Dataset("suite", "echo", List.of(new EvalCase("a", "p", ANY, true)));
        Verdict original = Verdict.of(dataset, List.of(pass("a")), 1.0, Set.of());

        Verdict parsed = Verdict.fromJson(original.toJson());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.gatePassed()).isTrue();
    }

    @Test
    void aRequiredIdAbsentFromTheDatasetFailsTheGate() {
        // Fail-closed: a typo'd --require (or a case that never made it into the
        // dataset) must not silently pass.
        Dataset dataset = new Dataset("suite", "echo", List.of(new EvalCase("a", "p", ANY)));

        Verdict verdict = Verdict.of(dataset, List.of(pass("a")), 1.0, Set.of("no-such-case"));

        assertThat(verdict.gatePassed()).isFalse();
        assertThat(verdict.requiredFailed()).containsExactly("no-such-case (not in dataset)");
    }

    @Test
    void emptyDatasetPasses() {
        Verdict verdict = Verdict.of(new Dataset("empty", "echo", List.of()), List.of(), 1.0, Set.of());
        assertThat(verdict.gatePassed()).isTrue();
        assertThat(verdict.passRate()).isEqualTo(1.0);
    }
}
