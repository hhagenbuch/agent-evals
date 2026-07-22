package io.github.hhagenbuch.evals.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;
import io.github.hhagenbuch.evals.model.EvalCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The machine-readable gate decision. The markdown report is for humans; this
 * is for the systems that act on the outcome (CI steps, agent-medic's
 * controller). One rule lives here and nowhere else:
 *
 * <p><b>gatePassed = aggregate threshold met AND no required case failed.</b>
 *
 * <p>Two emission channels, same content: a {@code verdict.json} file next to
 * the report, and a single {@code VERDICT-JSON: {...}} line on stdout — the
 * latter so consumers that only see a container's log (a Kubernetes Job's
 * pod log) still get the verdict without any shared volume.
 */
public record Verdict(
        String schema,
        String dataset,
        int total,
        long passed,
        double passRate,
        double minPassRate,
        boolean aggregatePassed,
        List<String> requiredFailed,
        boolean gatePassed,
        List<CaseVerdict> cases) {

    public static final String SCHEMA = "agent-evals/verdict/v1";
    /** Prefix of the single stdout verdict line. */
    public static final String STDOUT_TAG = "VERDICT-JSON: ";

    private static final ObjectMapper JSON = new ObjectMapper();

    public record CaseVerdict(String id, boolean passed, boolean required, long millis) {
    }

    /**
     * @param forcedRequired case ids additionally treated as required for this
     *                       run ({@code --require}), on top of any
     *                       {@code required: true} in the dataset itself
     */
    public static Verdict of(Dataset dataset, List<CaseResult> results,
                             double minPassRate, Set<String> forcedRequired) {
        List<CaseVerdict> cases = new ArrayList<>();
        List<String> requiredFailed = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            CaseResult result = results.get(i);
            boolean required = isRequired(dataset, i, forcedRequired);
            cases.add(new CaseVerdict(result.caseId(), result.passed(), required, result.millis()));
            if (required && !result.passed()) {
                requiredFailed.add(result.caseId());
            }
        }
        // Fail-closed: a --require id that matches no case is a broken gate
        // (typo, or the case never made it into the dataset), not a free pass.
        for (String id : forcedRequired) {
            if (dataset.cases().stream().noneMatch(c -> id.equals(c.id()))) {
                requiredFailed.add(id + " (not in dataset)");
            }
        }
        long passed = results.stream().filter(CaseResult::passed).count();
        double rate = results.isEmpty() ? 1.0 : (double) passed / results.size();
        boolean aggregatePassed = rate + 1e-9 >= minPassRate;
        return new Verdict(SCHEMA, dataset.name(), results.size(), passed, rate, minPassRate,
                aggregatePassed, List.copyOf(requiredFailed),
                aggregatePassed && requiredFailed.isEmpty(), List.copyOf(cases));
    }

    private static boolean isRequired(Dataset dataset, int index, Set<String> forcedRequired) {
        EvalCase evalCase = dataset.cases().get(index);
        return evalCase.isRequired() || forcedRequired.contains(evalCase.id());
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("failed to render verdict JSON", e);
        }
    }

    public static Verdict fromJson(String json) {
        try {
            return JSON.readValue(json, Verdict.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a valid verdict document: " + e.getMessage(), e);
        }
    }
}
