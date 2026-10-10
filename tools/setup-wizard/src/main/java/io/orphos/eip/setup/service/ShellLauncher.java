package io.orphos.eip.setup.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates a Bash interpreter and builds the command line for the repo's platform
 * launchers.
 *
 * <p>The launchers ({@code start.sh}, {@code stop.sh}, {@code startmodules.sh})
 * are the single implementation on every OS — there is no PowerShell port any
 * more. On Linux and macOS Bash is on {@code PATH}; on Windows it comes from Git
 * for Windows, so this walks the usual install locations. The {@code bash.exe} in
 * {@code System32} is deliberately skipped: that is the WSL launcher, which runs
 * in a different filesystem namespace (and often a different Docker context) than
 * the directory the wizard resolved.</p>
 *
 * <p>Scripts are always invoked as a RELATIVE {@code ./name.sh} with the process
 * working directory set to the platform root, so no Windows-vs-POSIX path
 * translation is needed on the argument list.</p>
 */
@Component
public class ShellLauncher {

    private static final Logger log = LogManager.getLogger(ShellLauncher.class);

    /** Where Git for Windows puts bash.exe, in the order we prefer them. */
    private static final String[] WINDOWS_ENV_ROOTS = {
            "ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"
    };

    private volatile Optional<String> cached;

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The bash executable to use, or empty when the host has none. */
    public Optional<String> bash() {
        Optional<String> c = cached;
        if (c == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = locate();
                    cached.ifPresentOrElse(
                            b -> log.info("Using bash: {}", b),
                            () -> log.warn("No bash interpreter found — the platform launchers cannot be run. "
                                    + "On Windows install Git for Windows, or set EIP_BASH."));
                }
                c = cached;
            }
        }
        return c;
    }

    /**
     * Builds {@code [bash, ./<script>, args...]}. Run it with the working
     * directory set to the platform root.
     */
    public List<String> command(String script, List<String> args) {
        String bash = bash().orElseThrow(() -> new IllegalStateException("No bash interpreter available"));
        List<String> cmd = new ArrayList<>();
        cmd.add(bash);
        cmd.add("./" + script);
        if (args != null) {
            cmd.addAll(args);
        }
        return cmd;
    }

    /** Human-readable install hint, used in the wizard's error output. */
    public String missingBashHint(String script) {
        if (isWindows()) {
            return "Bash was not found. The launchers are shell scripts shared by Windows, Linux and macOS.\n"
                    + "Install Git for Windows (it ships Git Bash): https://git-scm.com/download/win\n"
                    + "  winget install --id Git.Git -e\n"
                    + "Already have one elsewhere? Set EIP_BASH=C:\\path\\to\\bash.exe and restart the wizard.\n"
                    + "Or run it yourself:  start.bat  /  ./" + script + "\n";
        }
        return "Bash was not found on PATH. Install it (e.g. apt install bash) or run ./" + script + " manually.\n";
    }

    private Optional<String> locate() {
        String override = System.getenv("EIP_BASH");
        if (override != null && !override.isBlank() && Files.isExecutable(Path.of(override))) {
            return Optional.of(override);
        }
        if (!isWindows()) {
            for (String candidate : new String[]{"/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash"}) {
                if (Files.isExecutable(Path.of(candidate))) {
                    return Optional.of(candidate);
                }
            }
            // Last resort: let the OS resolve it from PATH.
            return Optional.of("bash");
        }
        for (String envKey : WINDOWS_ENV_ROOTS) {
            String root = System.getenv(envKey);
            if (root == null || root.isBlank()) continue;
            Path p = Path.of(root, "Git", "bin", "bash.exe");
            if (Files.isReadable(p)) return Optional.of(p.toString());
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path p = Path.of(localAppData, "Programs", "Git", "bin", "bash.exe");
            if (Files.isReadable(p)) return Optional.of(p.toString());
        }
        return fromPath();
    }

    /** Scans PATH for bash.exe, skipping the System32 WSL launcher. */
    private Optional<String> fromPath() {
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            if (dir.toLowerCase().contains("\\system32")) continue;
            try {
                Path p = Path.of(dir, "bash.exe");
                if (Files.isReadable(p)) return Optional.of(p.toString());
            } catch (RuntimeException ignored) {
                // Unparseable PATH entry — skip it.
            }
        }
        return Optional.empty();
    }
}
