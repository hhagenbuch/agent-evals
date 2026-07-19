# agent-evals

[![Eval gate](https://github.com/hhagenbuch/agent-evals/actions/workflows/eval-gate.yml/badge.svg)](https://github.com/hhagenbuch/agent-evals/actions)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Zero runtime deps](https://img.shields.io/badge/runtime%20deps-Jackson%20only-green)

**JUnit for LLM agents.** Golden datasets in YAML, deterministic assertions,
LLM-as-judge for the fuzzy parts, and an exit code you can gate CI on.

Agents regress silently: a prompt tweak fixes one behavior and breaks three
others. The fix is the same as it's always been in software — a regression
suite that runs on every change. This is that suite, in plain Java with no
framework to adopt.

## How it works

```yaml
# datasets/customer-support.yaml
name: customer-support
target: http://localhost:8080/api/chat
cases:
  - id: grounded-math
    prompt: "What is 973 * 481? Use your calculator tool."
    assert:
      - type: contains          # deterministic — always enforced
        value: "468013"
      - type: judge             # fuzzy — scored 1-5 by an LLM
        criteria: "States the correct product clearly and concisely."
        min_score: 4
```

```bash
mvn -q package
java -jar target/agent-evals-0.1.0-SNAPSHOT.jar datasets/customer-support.yaml
# [PASS] grounded-math (2140 ms)
# customer-support: 3/3 cases passed — report: eval-report.md
# exit code 0 → CI proceeds; any failure → exit 1 → CI blocks the merge
```

## Design

- **Two assertion tiers.** `contains` / `not_contains` / `regex` are
  deterministic and always run. `judge` calls a model with explicit criteria
  and a score threshold — and is **skipped, not failed**, when no
  `ANTHROPIC_API_KEY` is present, so the deterministic tier still gates
  keyless CI runs (see `eval-gate.yml`).
- **Targets are pluggable.** `HttpTarget` speaks the chat-endpoint shape of
  [spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter);
  `EchoTarget` lets the harness test itself. Implement `TargetSystem` (one
  method) for anything else.
- **Reports are artifacts.** Every run writes `eval-report.md` with per-case
  results, timings, and judge rationales — uploaded by CI on pass *and* fail,
  because the failing report is the useful one.

## Roadmap

- [x] YAML datasets, deterministic + judge assertions, markdown reports, CI gate
- [ ] Pass-rate threshold flag (`--min-pass-rate 0.9`) for flaky-tolerant gates
- [ ] Parallel case execution
- [ ] Trajectory assertions (did the agent call the *right tools*, not just answer well)
- [ ] Judge ensembling to reduce single-judge variance

## License

MIT
