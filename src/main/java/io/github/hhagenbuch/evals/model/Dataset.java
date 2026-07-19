package io.github.hhagenbuch.evals.model;

import java.util.List;

public record Dataset(String name, String target, List<EvalCase> cases) {
}
