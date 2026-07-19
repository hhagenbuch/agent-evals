package io.github.hhagenbuch.evals.target;

import java.util.List;

/**
 * What a {@link TargetSystem} returns for one prompt: the reply text plus the
 * trajectory (tool names the system reported using). {@code toolsUsed} is empty
 * for targets that don't expose a trace — {@code tool_called} assertions then
 * fail rather than silently pass.
 */
public record TargetResponse(String reply, List<String> toolsUsed) {

    public static TargetResponse of(String reply) {
        return new TargetResponse(reply, List.of());
    }
}
