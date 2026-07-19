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
import io.github.hhagenbuch.evals.target.TargetResponse;
import io.github.hhagenbuch.evals.target.TargetSystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * CLI entry point.
 *
 * <pre>
 * java -jar agent-evals.jar datasets/customer-support.yaml \
 *     [--target URL] [--report eval-report.md] [--min-pass-rate 0.9] \
 *     [--judge-ensemble 3] [--concurrency N]
 * </pre>
 *
 * Exit code 0 when the pass rate meets {@code --min-pass-rate} (default 1.0 —
 * every case must pass), 1 otherwise — wire it straight into CI.
 */
public final class EvalRunner {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: eval-runner <dataset.yaml> [--target URL] [--report FILE] "
                    + "[--min-pass-rate 0.9] [--judge-ensemble 3] [--judge-model MODEL] [--concurrency N]");
            System.exit(2);
        }
        Dataset dataset = DatasetLoader.load(Path.of(args[0]));
        String targetUrl = argValue(args, "--target", dataset.target());
        Path reportPath = Path.of(argValue(args, "--report", "eval-report.md"));
        double minPassRate = Double.parseDouble(argValue(args, "--min-pass-rate", "1.0"));
        int judgeEnsemble = Integer.parseInt(
                argValue(args, "--judge-ensemble", String.valueOf(LlmJudge.DEFAULT_ENSEMBLE)));
        String judgeModel = argValue(args, "--judge-model", LlmJudge.DEFAULT_MODEL);
        int concurrency = Integer.parseInt(argValue(args, "--concurrency", "0")); // 0 = unbounded

        TargetSystem target = targetUrl == null || targetUrl.equals("echo")
                ? new EchoTarget()
                : new HttpTarget(targetUrl);
        LlmJudge judge = new LlmJudge(System.getenv("ANTHROPIC_API_KEY"), judgeEnsemble, judgeModel);

        List<CaseResult> results = run(dataset, target, judge, concurrency);

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
        return run(dataset, target, judge, 0);
    }

    /**
     * Runs every case concurrently on virtual threads, then reports results in
     * dataset order (so the report and exit code stay deterministic regardless
     * of which case finishes first). {@code concurrency > 0} caps how many cases
     * run at once (via a {@link Semaphore}) so a big suite doesn't burst past the
     * target's or judge's rate limits; {@code 0} leaves it unbounded.
     */
    static List<CaseResult> run(Dataset dataset, TargetSystem target, LlmJudge judge, int concurrency) {
        Semaphore limiter = concurrency > 0 ? new Semaphore(concurrency) : null;
        List<CaseResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CaseResult>> futures = dataset.cases().stream()
                    .map(evalCase -> executor.submit(() -> gated(limiter, () -> evaluateCase(evalCase, target, judge))))
                    .toList();
            for (Future<CaseResult> future : futures) {
                CaseResult result = future.get();
                System.out.printf("[%s] %s (%d ms)%n",
                        result.passed() ? "PASS" : "FAIL", result.caseId(), result.millis());
                results.add(result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running evals", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("eval case failed unexpectedly", e.getCause());
        }
        return results;
    }

    /** Runs {@code work} while holding a permit from {@code limiter}, or directly when unbounded. */
    private static CaseResult gated(Semaphore limiter, java.util.function.Supplier<CaseResult> work)
            throws InterruptedException {
        if (limiter == null) {
            return work.get();
        }
        limiter.acquire();
        try {
            return work.get();
        } finally {
            limiter.release();
        }
    }

    static CaseResult evaluateCase(EvalCase evalCase, TargetSystem target, LlmJudge judge) {
        long start = System.currentTimeMillis();

        // Get the target's response first, isolated from the assertion loop: a
        // failure HERE means the target itself broke ("target responded").
        TargetResponse targetResponse;
        try {
            targetResponse = target.respond(evalCase.prompt());
        } catch (RuntimeException e) {
            return new CaseResult(evalCase.id(), "",
                    List.of(AssertionResult.fail("target responded", e.toString())),
                    System.currentTimeMillis() - start);
        }

        // Now the assertions. A failure HERE (e.g. the judge's network call) is
        // an assertion error, not a target error — attribute each one correctly
        // and don't let one broken assertion sink the others.
        List<AssertionResult> assertionResults = new ArrayList<>();
        for (var spec : evalCase.assertions()) {
            try {
                assertionResults.add(Assertions.evaluate(spec, evalCase.prompt(), targetResponse, judge));
            } catch (RuntimeException e) {
                assertionResults.add(AssertionResult.fail(
                        "assertion '" + spec.type() + "' evaluated", e.toString()));
            }
        }
        long millis = System.currentTimeMillis() - start;
        return new CaseResult(evalCase.id(), targetResponse.reply(), assertionResults, millis);
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
