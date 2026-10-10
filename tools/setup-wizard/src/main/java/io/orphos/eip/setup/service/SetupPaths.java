package io.orphos.eip.setup.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the single "platform root" the wizard operates on: where {@code .env}
 * and {@code orphos.config.json} are written, and where {@code start.sh} /
 * {@code startmodules.sh} are run from.
 *
 * <p>Why this is not just the working directory: when the wizard is launched as
 * the packaged {@code Orphos Setup.exe} (double-clicked), the process working
 * directory is the .exe's own folder — not the repo/distribution root. Defaulting
 * to "." there writes config into the app folder and fails to find the bring-up
 * scripts. So unless an explicit target is given, we walk UP from the working
 * directory to the nearest ancestor that looks like a platform root (it contains
 * one of the {@link #MARKERS}). This is correct for every launch style:</p>
 * <ul>
 *   <li>{@code mvnw} / {@code java -jar} from the repo root → the root itself matches.</li>
 *   <li>double-clicked {@code dist\Orphos Setup\Orphos Setup.exe} → walks up to the repo root.</li>
 *   <li>{@code <distribution>\Orphos Setup\Orphos Setup.exe} → walks up to the distribution root.</li>
 * </ul>
 *
 * <p>An explicit {@code ORPHOS_SETUP_TARGET_DIR} (or {@code -Dorphos.setup.target-dir})
 * always wins and is honoured verbatim.</p>
 */
@Component
public class SetupPaths {

    private static final Logger log = LogManager.getLogger(SetupPaths.class);

    /** Files that identify a platform root — must exist there for the bring-up to work. */
    private static final String[] MARKERS = {
            "startmodules.sh", "start.sh", "docker-compose.infra.yml"
    };

    private final Path targetDir;

    public SetupPaths(@Value("${orphos.setup.target-dir:.}") String configured) {
        this.targetDir = resolve(configured);
        log.info("Setup target directory: {}", targetDir);
    }

    /** The resolved, absolute platform root. */
    public Path targetDir() {
        return targetDir;
    }

    private static Path resolve(String configured) {
        // Explicit, non-default target (env var or -D property) is honoured as-is.
        if (configured != null && !configured.isBlank() && !configured.trim().equals(".")) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        // Otherwise auto-detect: walk up from the working directory to the nearest
        // ancestor that carries a platform-root marker.
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (hasMarker(dir)) {
                if (!dir.equals(start)) {
                    log.info("Working dir {} has no platform-root marker; using detected root {}", start, dir);
                }
                return dir;
            }
        }
        // No marker anywhere above us — fall back to the working directory. The UI
        // shows this path, and the runner reports the missing script clearly.
        log.warn("No platform-root marker ({}) found from {} upward — defaulting to the working directory. "
                + "Run from the repo/distribution root, or set ORPHOS_SETUP_TARGET_DIR.",
                String.join(" / ", MARKERS), start);
        return start;
    }

    private static boolean hasMarker(Path dir) {
        for (String marker : MARKERS) {
            if (Files.isRegularFile(dir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }
}
