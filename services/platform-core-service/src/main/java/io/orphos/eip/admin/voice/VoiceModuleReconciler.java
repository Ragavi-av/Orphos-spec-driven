package io.orphos.eip.admin.voice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the voice module (eip-whisper + eip-voice) exactly when some tenant has
 * voice switched on.
 *
 * <p>The per-tenant toggle in Admin → Voice is the single switch. The containers
 * are shared by every tenant, so the desired state is "running" while at least
 * one active tenant is on and "stopped" when none is; the per-tenant gate in
 * voice-service still decides who actually sees dictation.</p>
 *
 * <p>Level-triggered, not edge-triggered: every pass compares the toggle rows
 * with Docker's state and corrects the difference. That one loop covers a fresh
 * launch (the launchers create both containers stopped), a host or daemon
 * restart (both are {@code restart: "no"}), a Whisper crash, and a toggle made
 * a moment ago. When the toggle rows cannot be read, nothing is changed —
 * an unreadable store must never be taken as "no tenant wants voice".</p>
 */
@Component
public class VoiceModuleReconciler {

    private static final Logger log = LogManager.getLogger(VoiceModuleReconciler.class);

    /** Whisper first: voice-service probes it on its status checks. Stopped in reverse. */
    static final List<String> CONTAINERS = List.of("eip-whisper", "eip-voice");

    /** Docker states a {@code start} brings to running. */
    private static final Set<String> STARTABLE = Set.of("created", "exited");
    /** Docker states that hold resources and so need a {@code stop} when voice is off. */
    private static final Set<String> STOPPABLE = Set.of("running", "paused", "restarting");

    private final VoiceFeatureStore features;
    private final DockerSocketClient docker;
    private final boolean autoManaged;

    /** Missing containers are reported once per run of this service, not every pass. */
    private boolean missingReported;

    public VoiceModuleReconciler(VoiceFeatureStore features,
                                 DockerSocketClient docker,
                                 @Value("${eip.voice.autostart.enabled:true}") boolean autoManaged) {
        this.features = features;
        this.docker = docker;
        this.autoManaged = autoManaged;
    }

    /**
     * The module's state as the Voice tab shows it.
     *
     * @param autoManaged     whether this reconciler owns the lifecycle
     * @param enabledTenants  tenants voice is on for, or -1 when the toggle rows could not be read
     * @param desired         "running", "stopped" or "unknown"
     * @param containers      Docker state per container ("missing" when not created, "unknown" on error)
     * @param actions         what this pass did, e.g. "start eip-whisper"
     */
    public record State(boolean autoManaged, int enabledTenants, String desired,
                        Map<String, String> containers, List<String> actions) {}

    @Scheduled(initialDelayString = "${eip.voice.autostart.initial-delay-ms:20000}",
               fixedDelayString   = "${eip.voice.autostart.interval-ms:30000}")
    void scheduledPass() {
        if (!autoManaged) return;
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Voice module reconcile pass failed: {}", e.getMessage());
        }
    }

    /** Reads the current state without changing anything. */
    public synchronized State inspect() {
        return pass(false);
    }

    /** Brings the containers to the state the toggles ask for, then reports it. */
    public synchronized State reconcile() {
        return pass(autoManaged);
    }

    private State pass(boolean act) {
        int enabled;
        try {
            enabled = features.enabledTenantIds().size();
        } catch (RuntimeException e) {
            // Normal for the first seconds of a cold start (ArcadeDB still coming
            // up, or the registry not yet having created TenantFeature).
            log.debug("Voice toggles unreadable, leaving the module as it is: {}", e.getMessage());
            enabled = -1;
        }
        String desired = enabled < 0 ? "unknown" : enabled > 0 ? "running" : "stopped";

        Map<String, String> states = new LinkedHashMap<>();
        for (String name : CONTAINERS) {
            states.put(name, stateOf(name));
        }

        List<String> actions = new ArrayList<>();
        if (act && enabled >= 0) {
            reportMissing(states);
            if (enabled > 0) {
                for (String name : CONTAINERS) {
                    String s = states.get(name);
                    if (STARTABLE.contains(s))    apply(name, "start", enabled, actions, states);
                    else if ("paused".equals(s))  apply(name, "unpause", enabled, actions, states);
                }
            } else {
                for (String name : CONTAINERS.reversed()) {
                    if (STOPPABLE.contains(states.get(name))) apply(name, "stop", enabled, actions, states);
                }
            }
        }
        return new State(autoManaged, enabled, desired, states, actions);
    }

    private String stateOf(String name) {
        try {
            Optional<String> s = docker.containerState(name);
            return s.orElse("missing");
        } catch (RuntimeException e) {
            log.debug("Could not inspect {}: {}", name, e.getMessage());
            return "unknown";
        }
    }

    private void apply(String name, String action, int enabled,
                       List<String> actions, Map<String, String> states) {
        try {
            if (docker.action(name, action)) {
                log.info("Voice module: {} {} ({} tenant(s) have voice on)", action, name, enabled);
                actions.add(action + " " + name);
                states.put(name, stateOf(name));
            } else {
                log.warn("Voice module: Docker refused to {} {}", action, name);
            }
        } catch (RuntimeException e) {
            log.warn("Voice module: could not {} {}: {}", action, name, e.getMessage());
        }
    }

    private void reportMissing(Map<String, String> states) {
        if (missingReported) return;
        List<String> missing = states.entrySet().stream()
                .filter(e -> "missing".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty()) {
            missingReported = true;
            log.warn("Voice containers {} do not exist on this host, so voice cannot run. "
                    + "Set EIP_VOICE_ENABLED=true in .env and re-run the launcher.", missing);
        }
    }
}
