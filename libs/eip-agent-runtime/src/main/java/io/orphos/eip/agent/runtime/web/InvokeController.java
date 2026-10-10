package io.orphos.eip.agent.runtime.web;

import io.orphos.eip.agent.runtime.AgentEngine;
import io.orphos.eip.agent.runtime.AgentResult;
import io.orphos.eip.agent.spec.AgentInvocation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The uniform {@code POST /invoke} endpoint every agent exposes (TO-BE design §8). Provided by the
 * runtime so a standalone agent never writes it — the orchestrator posts an {@link AgentInvocation}
 * here and receives the {@link AgentResult}.
 */
@RestController
public class InvokeController {

    private final AgentEngine engine;

    public InvokeController(AgentEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/invoke")
    public AgentResult invoke(@RequestBody AgentInvocation invocation) {
        return engine.handle(invocation);
    }
}
