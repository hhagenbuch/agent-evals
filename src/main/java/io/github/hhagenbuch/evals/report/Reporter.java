package io.github.hhagenbuch.evals.report;

import io.github.hhagenbuch.evals.model.AssertionResult;
import io.github.hhagenbuch.evals.model.CaseResult;
import io.github.hhagenbuch.evals.model.Dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes a markdown report and a console summary; used by CI as the gate artifact. */
public final class Reporter {

    private Reporter() {
    }

    public static String markdown(Dataset dataset, List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        StringBuilder md = new StringBuilder();
        md.append("# Eval report — ").append(dataset.name()).append("\n\n");
        md.append("**").append(passed).append("/").append(results.size()).append(" cases passed**\n\n");
        md.append("| Case | Result | Time | Details |\n|---|---|---|---|\n");
        for (CaseResult result : results) {
            md.append("| ").append(result.caseId())
              .append(" | ").append(result.passed() ? "✅" : "❌")
              .append(" | ").append(result.millis()).append(" ms | ");
            for (AssertionResult a : result.assertions()) {
                String mark = a.skipped() ? "⏭" : a.passed() ? "✓" : "✗";
                md.append(mark).append(" ").append(a.description());
                if (!a.detail().isBlank()) {
                    md.append(" — ").append(a.detail());
                }
                md.append("<br>");
            }
            md.append(" |\n");
        }
        return md.toString();
    }

    public static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
