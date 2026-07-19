package io.github.hhagenbuch.evals;

import io.github.hhagenbuch.evals.assertions.Assertions;
import io.github.hhagenbuch.evals.dataset.DatasetLoader;
import io.github.hhagenbuch.evals.judge.LlmJudge;
import io.github.hhagenbuch.evals.model.AssertionResult;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;
import io.github.hhagenbuch.evals.model.EvalCase;
import io.github.hhagenbuch.evals.report.Reporter;
import io.github.hhagenbuch.evals.target.EchoTarget;
import io.github.hhagenbuch.evals.target.HttpTarget;
import io.github.hhagenbuch.evals.target.TargetSystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point.
 *
 * <pre>
 * java -jar agent-evals.jar datasets/customer-support.yaml \
 *     [--target URL] [--report eval-report.md] [--min-pass-rate 0.9]
 * </pre>
 *
 * Exit code 0 when the pass rate meets {@code --min-pass-rate} (default 1.0 —
 * every case must pass), 1 otherwise — wire it straight into CI.
 */
public final class EvalRunner {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println(
                    "usage: eval-runner <dataset.yaml> [--target URL] [--report FILE] [--min-pass-rate 0.9]");
            System.exit(2);
        }
        Dataset dataset = DatasetLoader.load(Path.of(args[0]));
        String targetUrl = argValue(args, "--target", dataset.target());
        Path reportPath = Path.of(argValue(args, "--report", "eval-report.md"));
        double minPassRate = Double.parseDouble(argValue(args, "--min-pass-rate", "1.0"));

        TargetSystem target = targetUrl == null || targetUrl.equals("echo")
                ? new EchoTarget()
                : new HttpTarget(targetUrl);
        LlmJudge judge = new LlmJudge(System.getenv("ANTHROPIC_API_KEY"));

        List<CaseResult> results = run(dataset, target, judge);

        String md = Reporter.markdown(dataset, results);
        Reporter.write(reportPath, md);
        long passed = results.stream().filter(CaseResult::passed).count();
        boolean gatePassed = meetsThreshold(passed, results.size(), minPassRate);
        System.out.printf("%n%s: %d/%d cases passed (min-pass-rate %.2f) — report: %s%n",
                dataset.name(), passed, results.size(), minPassRate, reportPath);
        System.exit(gatePassed ? 0 : 1);
    }

    /** CI gate: does the observed pass rate meet the threshold? Empty datasets pass. */
    static boolean meetsThreshold(long passed, int total, double minPassRate) {
        double rate = total == 0 ? 1.0 : (double) passed / total;
        return rate + 1e-9 >= minPassRate;
    }

    static List<CaseResult> run(Dataset dataset, TargetSystem target, LlmJudge judge) {
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : dataset.cases()) {
            long start = System.currentTimeMillis();
            String response;
            List<AssertionResult> assertionResults = new ArrayList<>();
            try {
                response = target.respond(evalCase.prompt());
                for (var spec : evalCase.assertions()) {
                    assertionResults.add(Assertions.evaluate(spec, evalCase.prompt(), response, judge));
                }
            } catch (RuntimeException e) {
                response = "";
                assertionResults.add(AssertionResult.fail("target responded", e.toString()));
            }
            long millis = System.currentTimeMillis() - start;
            CaseResult result = new CaseResult(evalCase.id(), response, assertionResults, millis);
            System.out.printf("[%s] %s (%d ms)%n", result.passed() ? "PASS" : "FAIL", evalCase.id(), millis);
            results.add(result);
        }
        return results;
    }

    private static String argValue(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
