package io.github.hhagenbuch.evals.target;

/** The system under evaluation: given a prompt, produce a response. */
public interface TargetSystem {

    TargetResponse respond(String prompt);
}
