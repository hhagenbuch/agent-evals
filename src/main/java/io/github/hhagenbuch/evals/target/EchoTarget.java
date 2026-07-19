package io.github.hhagenbuch.evals.target;

/** Trivial target for smoke tests and harness self-tests: echoes the prompt. */
public final class EchoTarget implements TargetSystem {

    @Override
    public TargetResponse respond(String prompt) {
        return TargetResponse.of("ECHO: " + prompt);
    }
}
