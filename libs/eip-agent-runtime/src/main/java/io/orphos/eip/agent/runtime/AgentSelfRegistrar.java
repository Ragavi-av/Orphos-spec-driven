package io.orphos.eip.agent.runtime;

import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.agent.spec.AgentRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

/**
 * Self-registers the agent's {@link AgentManifest} with platform-registry at startup
 * (TO-BE design §6). Best-effort: a registry that is briefly unavailable must not stop the agent
 * from booting — the orchestrator re-reads the registry continuously, so a later re-register (or an
 * admin action) still surfaces the agent.
 */
@Order(20)
public class AgentSelfRegistrar implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(AgentSelfRegistrar.class);

    private final AgentManifest manifest;
    private final RegistryClient registry;
    private final AgentRuntimeProperties props;

    public AgentSelfRegistrar(AgentManifest manifest, RegistryClient registry, AgentRuntimeProperties props) {
        this.manifest = manifest;
        this.registry = registry;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isRegisterOnStartup()) {
            log.info("Agent self-registration disabled (eip.agent.runtime.register-on-startup=false).");
            return;
        }
        if (manifest == null) {
            log.warn("No AgentManifest bean — skipping self-registration.");
            return;
        }
        String baseUrl = props.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("eip.agent.runtime.base-url is unset — skipping self-registration for '{}'.",
                    manifest.agentId());
            return;
        }
        String healthUrl = stripTrailingSlash(baseUrl) + props.getHealthPath();
        registry.registerAgent(new AgentRegistration(manifest, baseUrl, healthUrl));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
