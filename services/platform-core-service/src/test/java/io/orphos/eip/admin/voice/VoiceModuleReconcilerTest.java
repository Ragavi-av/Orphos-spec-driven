package io.orphos.eip.admin.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceModuleReconcilerTest {

    /** Toggle rows, or a read failure. */
    private static final class FakeFeatures extends VoiceFeatureStore {
        Set<String> enabled = Set.of();
        boolean fail;

        FakeFeatures() { super(null, null); }

        @Override
        public Set<String> enabledTenantIds() {
            if (fail) throw new IllegalStateException("ArcadeDB down");
            return enabled;
        }
    }

    /** Docker with a start/stop that actually changes the reported state. */
    private static final class FakeDocker extends DockerSocketClient {
        final Map<String, String> states = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<String> containerState(String name) {
            return Optional.ofNullable(states.get(name));
        }

        @Override
        public boolean action(String name, String action) {
            calls.add(action + " " + name);
            states.put(name, "stop".equals(action) ? "exited" : "running");
            return true;
        }
    }

    private final FakeFeatures features = new FakeFeatures();
    private final FakeDocker docker = new FakeDocker();

    private VoiceModuleReconciler reconciler(boolean autoManaged) {
        return new VoiceModuleReconciler(features, docker, autoManaged);
    }

    @Test
    void enabledTenantStartsWhisperThenVoice() {
        features.enabled = Set.of("zenitus");
        docker.states.put("eip-whisper", "created");
        docker.states.put("eip-voice", "exited");

        VoiceModuleReconciler.State s = reconciler(true).reconcile();

        assertEquals(List.of("start eip-whisper", "start eip-voice"), docker.calls);
        assertEquals("running", s.desired());
        assertEquals("running", s.containers().get("eip-whisper"));
        assertEquals(1, s.enabledTenants());
    }

    @Test
    void noEnabledTenantStopsVoiceThenWhisper() {
        docker.states.put("eip-whisper", "running");
        docker.states.put("eip-voice", "running");

        reconciler(true).reconcile();

        assertEquals(List.of("stop eip-voice", "stop eip-whisper"), docker.calls);
    }

    @Test
    void noEnabledTenantLeavesStoppedContainersAlone() {
        docker.states.put("eip-whisper", "created");
        docker.states.put("eip-voice", "created");

        reconciler(true).reconcile();

        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void unreadableTogglesNeverStopARunningModule() {
        features.fail = true;
        docker.states.put("eip-whisper", "running");
        docker.states.put("eip-voice", "running");

        VoiceModuleReconciler.State s = reconciler(true).reconcile();

        assertTrue(docker.calls.isEmpty());
        assertEquals("unknown", s.desired());
        assertEquals(-1, s.enabledTenants());
    }

    @Test
    void alreadyRunningIsANoOp() {
        features.enabled = Set.of("zenitus");
        docker.states.put("eip-whisper", "running");
        docker.states.put("eip-voice", "running");

        reconciler(true).reconcile();

        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void missingContainerDoesNotBlockTheOtherOne() {
        features.enabled = Set.of("zenitus");
        docker.states.put("eip-voice", "created");   // whisper never created

        VoiceModuleReconciler.State s = reconciler(true).reconcile();

        assertEquals(List.of("start eip-voice"), docker.calls);
        assertEquals("missing", s.containers().get("eip-whisper"));
    }

    @Test
    void autostartOffOnlyReports() {
        features.enabled = Set.of("zenitus");
        docker.states.put("eip-whisper", "created");
        docker.states.put("eip-voice", "created");

        VoiceModuleReconciler.State s = reconciler(false).reconcile();

        assertTrue(docker.calls.isEmpty());
        assertEquals("running", s.desired());
    }

    @Test
    void inspectNeverActs() {
        features.enabled = Set.of("zenitus");
        docker.states.put("eip-whisper", "exited");
        docker.states.put("eip-voice", "exited");

        reconciler(true).inspect();

        assertTrue(docker.calls.isEmpty());
    }
}
