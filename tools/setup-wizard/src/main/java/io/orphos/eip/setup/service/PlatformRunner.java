package io.orphos.eip.setup.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives the layered platform bring-up on the operator's behalf and streams the
 * combined stdout/stderr back so the wizard can show a live install log.
 *
 * <p>It only ever invokes the repo's own {@code start.sh} / {@code startmodules.sh}
 * scripts (never an arbitrary command), and the functionality arguments are
 * validated against the catalogue by the caller and passed as discrete process
 * args — so there is no shell-injection surface.</p>
 *
 * <p>Those two scripts are the single launcher implementation for every OS, so
 * the wizard's automated start now works on Windows (via Git Bash), Linux and
 * macOS alike.</p>
 */
@Service
public class PlatformRunner {

    private static final Logger log = LogManager.getLogger(PlatformRunner.class);

    private final Path targetDir;
    private final ShellLauncher shell;

    public PlatformRunner(SetupPaths paths, ShellLauncher shell) {
        this.targetDir = paths.targetDir();
        this.shell = shell;
    }

    /**
     * Runs the bring-up and streams each output line to {@code out}. {@code mode}
     * "full" runs {@code start.sh}; anything else runs
     * {@code startmodules.sh <feat1>,<feat2>} (falling back to {@code admin} —
     * core UI only — when no functionalities are given).
     */
    public void stream(String mode, List<String> functionalities, OutputStream out) throws IOException {
        // A client package takes a different path entirely. Its launcher is
        // PowerShell (which ships with Windows) rather than the repo's shell
        // scripts, because a client has no bash and no source tree: the .sh
        // launchers would need Git Bash, and they run Maven and build images.
        // orphos-start.ps1 loads the prebuilt archive and starts the layers.
        if (streamDistribution(out)) {
            return;
        }

        boolean full = "full".equalsIgnoreCase(mode);
        String script = full ? "start.sh" : "startmodules.sh";
        Path scriptPath = targetDir.resolve(script);

        if (!Files.isReadable(scriptPath)) {
            write(out, "[runner] Could not find " + scriptPath + " — run the wizard from the repo root.\n");
            return;
        }
        if (shell.bash().isEmpty()) {
            write(out, "[runner] " + shell.missingBashHint(script));
            return;
        }

        List<String> cmd = shell.command(script, full ? List.of() : List.of(featArg(functionalities)));

        write(out, "[runner] working dir: " + targetDir + "\n");
        write(out, "[runner] $ " + String.join(" ", cmd) + "\n\n");

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(new File(targetDir.toString()))
                    .redirectErrorStream(true);
            // The launchers only pause on failure when run through the .bat
            // wrapper, but keep this set: the wizard has no console to type into.
            pb.environment().put("EIP_NO_PAUSE", "1");
            p = pb.start();
        } catch (IOException e) {
            write(out, "[runner] Failed to launch " + script + ": " + e.getMessage() + "\n");
            return;
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                write(out, line + "\n");
            }
        }

        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            write(out, "\n[runner] interrupted — process cancelled.\n");
            return;
        }
        write(out, "\n[runner] " + script + " exited with code " + code
                + (code == 0 ? "  [OK]" : "  [FAILED - check the log above]") + "\n");
        log.info("Platform bring-up ({}) exited with code {}", script, code);
    }

    /**
     * Runs the distribution's own PowerShell launcher when this is a client
     * package, streaming its output. Returns false when this is an ordinary
     * checkout, so the caller falls through to the shared shell launchers.
     *
     * <p>Both markers must be present: {@code .orphos-distribution} says the
     * images are prebuilt, and {@code orphos-start.ps1} is the launcher itself.
     * Either alone means a half-assembled package, and it is better to fall
     * through than to run the wrong thing.</p>
     */
    private boolean streamDistribution(OutputStream out) throws IOException {
        Path marker = targetDir.resolve(".orphos-distribution");
        Path launcher = targetDir.resolve("orphos-start.ps1");
        if (!Files.isReadable(marker) || !Files.isReadable(launcher)) {
            return false;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            write(out, "[runner] This is a distribution package, whose launcher is PowerShell-only.\n"
                    + "[runner] Run  pwsh ./orphos-start.ps1  from " + targetDir + " yourself.\n");
            return true;
        }

        List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", launcher.toString());
        write(out, "[runner] distribution package - using the bundled launcher (no bash, no Maven).\n");
        write(out, "[runner] working dir: " + targetDir + "\n");
        write(out, "[runner] $ " + String.join(" ", cmd) + "\n\n");

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(new File(targetDir.toString()))
                    .redirectErrorStream(true);
            // Point the shared launchers at the bundled bash too, so start.bat
            // works in the package for anyone who prefers it.
            Path bundledBash = targetDir.resolve("tools").resolve("bash").resolve("bin").resolve("bash.exe");
            if (Files.isExecutable(bundledBash)) {
                pb.environment().put("EIP_BASH", bundledBash.toString());
            }
            pb.environment().put("EIP_NO_PAUSE", "1");
            p = pb.start();
        } catch (IOException e) {
            write(out, "[runner] Failed to launch orphos-start.ps1: " + e.getMessage() + "\n");
            return true;
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                write(out, line + "\n");
            }
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            write(out, "\n[runner] interrupted — process cancelled.\n");
            return true;
        }
        write(out, "\n[runner] orphos-start.ps1 exited with code " + code
                + (code == 0 ? "  [OK]" : "  [FAILED - check the log above]") + "\n");
        log.info("Distribution bring-up exited with code {}", code);
        return true;
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String featArg(List<String> functionalities) {
        if (functionalities == null || functionalities.isEmpty()) {
            return "admin"; // core UI only
        }
        return String.join(",", functionalities);
    }
}
