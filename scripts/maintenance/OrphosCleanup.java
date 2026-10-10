import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reclaims disk space from regenerable build output in an Orphos checkout.
 *
 * <p>Why this exists: an Orphos working tree grows without bound because every
 * artefact it produces is written <em>beside</em> the source and none of it is
 * ever collected. A single {@code distribution} packaging run leaves the
 * exported Docker image tarball, the unpacked client tree AND the zip of that
 * same tree on disk at once — three copies of the same bytes — and the next run
 * adds a {@code slim} variant next to it rather than replacing it. Maven leaves
 * a {@code target/} per module, Vite leaves a {@code dist/} per remote, and the
 * Docker builder cache is never pruned at all. None of it is in git (see
 * {@code .gitignore}), so none of it is ever cleaned up by a branch switch.
 * Everything this script removes is reproducible by re-running the build that
 * made it.</p>
 *
 * <p>Run (JDK 21, single-file source launch — no compile step):</p>
 * <pre>
 *   java scripts/maintenance/OrphosCleanup.java                 # dry run, changes nothing
 *   java scripts/maintenance/OrphosCleanup.java --apply         # actually delete
 *   java scripts/maintenance/OrphosCleanup.java --apply --docker
 * </pre>
 *
 * <p><b>Dry run is the default.</b> Nothing is deleted unless {@code --apply}
 * is passed, so the safe way to use this is always to run it bare first and
 * read the table it prints.</p>
 *
 * <h2>Options</h2>
 * <pre>
 *   --apply                 Perform deletions (default: report only)
 *   --root=PATH             Checkout to clean (default: current directory)
 *   --older-than-days=N     Only touch things not modified in N days (default: 0 = all)
 *   --node                  Also delete node_modules/ (needs npm install to restore)
 *   --docker                Also prune Docker build cache + dangling images
 *   --docker-volumes        Also prune DANGLING Docker volumes -- see warning below
 *   --keep=cat1,cat2        Skip categories (build,dist,logs,backups,temp)
 *   --help                  Print this list
 * </pre>
 *
 * <h2>Warning on {@code --docker-volumes}</h2>
 * <p>A "dangling" volume is one no container currently references — which
 * includes the volumes of a stack that is merely stopped. Pruning them while
 * Orphos is down destroys Postgres, Qdrant and Keycloak state. The flag is
 * opt-in, refuses to run unless the stack is up, and is not implied by
 * {@code --docker}.</p>
 *
 * <p>Never touched, regardless of flags: {@code .git/}, {@code .env},
 * {@code orphos.config.json}, and {@code config/fuseki/*.tar.gz} (a 42 MB
 * download that archive.apache.org serves at single-digit KB/s).</p>
 */
public final class OrphosCleanup {

    /** A group of removable things, shown as one row in the report. */
    private enum Category {
        BUILD   ("build",   "Maven target/, frontend dist/, Vite caches",       true),
        DIST    ("dist",    "distribution/ output (image tars, client zips)",   true),
        LOGS    ("logs",    "*.log, rotated logs, JVM crash + heap dumps",      true),
        BACKUPS ("backups", "*.bak*, *.discarded, wizard-generated env copies", true),
        TEMP    ("temp",    "*.tmp and editor leftovers",                       true),
        NODE    ("node",    "node_modules/ (npm install restores)",             false);

        final String flag;
        final String description;
        final boolean onByDefault;

        Category(String flag, String description, boolean onByDefault) {
            this.flag = flag;
            this.description = description;
            this.onByDefault = onByDefault;
        }
    }

    /** One removable path, already sized. */
    private record Finding(Category category, Path path, long bytes, boolean directory) {}

    /** Directory names that are build output wherever they appear. */
    private static final Set<String> BUILD_DIRS = Set.of("target", "dist", ".vite", ".parcel-cache");

    /**
     * Paths (relative, slash-separated) that are never candidates.
     *
     * <p>{@code .claude} is protected because it holds live git worktrees for
     * parallel agent sessions, which may carry uncommitted work. Their build
     * output is regenerable like any other, but a worktree is not ours to
     * reach into: it is retired with {@code git worktree remove}, which also
     * clears the registration in {@code .git/worktrees} that deleting the
     * files by hand would leave stale. {@link #reportWorktrees} surfaces them
     * instead.</p>
     */
    private static final Set<String> PROTECTED = Set.of(
            ".git", ".claude", ".env", "orphos.config.json", "config/fuseki");

    private static final long DAY_MILLIS = 86_400_000L;

    private final Path root;
    private final boolean apply;
    private final long olderThanDays;
    private final EnumSet<Category> enabled;
    private final List<Finding> findings = new ArrayList<>();

    private OrphosCleanup(Path root, boolean apply, long olderThanDays, EnumSet<Category> enabled) {
        this.root = root;
        this.apply = apply;
        this.olderThanDays = olderThanDays;
        this.enabled = enabled;
    }

    public static void main(String[] args) throws Exception {
        List<String> argv = List.of(args);
        if (argv.contains("--help") || argv.contains("-h")) {
            printHelp();
            return;
        }

        Path root = Paths.get(value(argv, "--root", ".")).toAbsolutePath().normalize();
        requireOrphosCheckout(root);

        boolean apply = argv.contains("--apply");
        long days = Long.parseLong(value(argv, "--older-than-days", "0"));

        EnumSet<Category> enabled = EnumSet.noneOf(Category.class);
        for (Category c : Category.values()) {
            if (c.onByDefault) enabled.add(c);
        }
        if (argv.contains("--node")) enabled.add(Category.NODE);
        for (String keep : value(argv, "--keep", "").split(",")) {
            if (keep.isBlank()) continue;
            for (Category c : Category.values()) {
                if (c.flag.equalsIgnoreCase(keep.trim())) enabled.remove(c);
            }
        }

        System.out.printf("Orphos cleanup - %s%n", root);
        System.out.printf("Mode: %s%s%n%n",
                apply ? "APPLY (deleting)" : "DRY RUN (nothing will be deleted)",
                days > 0 ? "  |  only items older than " + days + " day(s)" : "");

        OrphosCleanup cleanup = new OrphosCleanup(root, apply, days, enabled);
        cleanup.scan();
        cleanup.report();
        if (apply) cleanup.delete();
        reportWorktrees(root);

        if (argv.contains("--docker")) {
            pruneDocker(apply, argv.contains("--docker-volumes"));
        } else if (argv.contains("--docker-volumes")) {
            System.out.println("\n--docker-volumes ignored: it only applies together with --docker.");
        }

        if (!apply) {
            System.out.println("\nNothing was deleted. Re-run with --apply to reclaim the space above.");
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────

    private void scan() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;

                String rel = relative(dir);
                String name = dir.getFileName().toString();

                if (isProtected(rel)) return FileVisitResult.SKIP_SUBTREE;

                // node_modules is never descended into: whether or not we are
                // deleting it, walking 43k files inside it only wastes time.
                if (name.equals("node_modules")) {
                    if (enabled.contains(Category.NODE)) record(Category.NODE, dir, true);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (BUILD_DIRS.contains(name)) {
                    if (enabled.contains(Category.BUILD)) record(Category.BUILD, dir, true);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (isDistributionOutput(rel)) {
                    if (enabled.contains(Category.DIST)) record(Category.DIST, dir, true);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(file);
                if (isProtected(rel)) return FileVisitResult.CONTINUE;

                if (isDistributionOutput(rel)) {
                    if (enabled.contains(Category.DIST)) record(Category.DIST, file, false);
                    return FileVisitResult.CONTINUE;
                }
                Category c = classifyFile(file.getFileName().toString());
                if (c != null && enabled.contains(c)) record(c, file, false);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;   // locked / permission denied: skip, do not abort
            }
        });
    }

    /** Which category a loose file belongs to, or {@code null} if it stays. */
    private static Category classifyFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);

        // Heap and crash dumps: written next to the JVM that died, often GB-sized,
        // and easy to forget about after a memory investigation.
        if (n.endsWith(".hprof") || n.startsWith("hs_err_pid") || n.startsWith("replay_pid")) {
            return Category.LOGS;
        }
        if (n.endsWith(".log") || n.matches(".*\\.log\\.\\d+") || n.endsWith(".log.gz")) {
            return Category.LOGS;
        }
        if (n.endsWith(".discarded") || n.endsWith(".bak") || n.contains(".bak-")
                || n.contains(".bak.") || n.contains(".pre-pull.bak")
                || n.startsWith(".env.wizard-generated.")) {
            return Category.BACKUPS;
        }
        if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith("~") || n.endsWith(".orig")) {
            return Category.TEMP;
        }
        return null;
    }

    /**
     * True for the packaging outputs directly under {@code distribution/}: the
     * unpacked {@code Orphos} and {@code slim} trees and the archives built from
     * them. Hand-maintained files that live there ({@code docs/}, loose
     * {@code .java} probes) are left alone.
     */
    private static boolean isDistributionOutput(String rel) {
        if (!rel.startsWith("distribution/")) return false;
        String[] parts = rel.split("/");
        if (parts.length != 2) return false;
        String child = parts[1];
        return child.equals("Orphos") || child.equals("slim")
                || child.endsWith(".zip") || child.endsWith(".tar") || child.endsWith(".tar.gz");
    }

    private static boolean isProtected(String rel) {
        for (String p : PROTECTED) {
            if (rel.equals(p) || rel.startsWith(p + "/")) return true;
        }
        return rel.startsWith(".git/") || rel.contains("/.git/");
    }

    private void record(Category category, Path path, boolean directory) {
        if (olderThanDays > 0 && !isOlderThanCutoff(path)) return;
        long bytes = sizeOf(path);
        if (bytes == 0 && !directory) return;      // empty files are not worth listing
        findings.add(new Finding(category, path, bytes, directory));
    }

    private boolean isOlderThanCutoff(Path path) {
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();
            return age >= olderThanDays * DAY_MILLIS;
        } catch (IOException e) {
            return false;                          // cannot tell how old it is: leave it
        }
    }

    private String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    // ── Reporting ─────────────────────────────────────────────────────────

    private void report() {
        if (findings.isEmpty()) {
            System.out.println("Nothing to reclaim - the checkout is already clean.");
            return;
        }

        Map<Category, long[]> totals = new LinkedHashMap<>();   // [bytes, count]
        for (Finding f : findings) {
            long[] t = totals.computeIfAbsent(f.category(), k -> new long[2]);
            t[0] += f.bytes();
            t[1]++;
        }

        System.out.printf("%-10s %12s %8s   %s%n", "CATEGORY", "SIZE", "ITEMS", "WHAT IT IS");
        System.out.println("-".repeat(88));
        long grand = 0;
        for (Category c : Category.values()) {
            long[] t = totals.get(c);
            if (t == null) continue;
            grand += t[0];
            System.out.printf("%-10s %12s %8d   %s%n", c.flag, human(t[0]), t[1], c.description);
        }
        System.out.println("-".repeat(88));
        System.out.printf("%-10s %12s%n%n", "TOTAL", human(grand));

        System.out.println("Largest items:");
        findings.stream()
                .sorted(Comparator.comparingLong(Finding::bytes).reversed())
                .limit(15)
                .forEach(f -> System.out.printf("  %10s  %s%s%n",
                        human(f.bytes()), relative(f.path()), f.directory() ? "/" : ""));
    }

    // ── Deletion ──────────────────────────────────────────────────────────

    private void delete() {
        System.out.println("\nDeleting...");
        long freed = 0;
        int failed = 0;
        for (Finding f : findings) {
            try {
                deleteRecursively(f.path());
                freed += f.bytes();
            } catch (IOException e) {
                failed++;
                System.out.printf("  could not remove %s - %s%n", relative(f.path()), e.getMessage());
            }
        }
        System.out.printf("Reclaimed %s across %d item(s)%s.%n",
                human(freed), findings.size() - failed,
                failed > 0 ? " (" + failed + " skipped - likely in use)" : "");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            List<Path> entries;
            try (var walk = Files.walk(path)) {
                // Deepest first, so a directory is always empty by the time we
                // reach it. Collected eagerly: the stream must not outlive the
                // tree it is walking.
                entries = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : entries) {
                Files.deleteIfExists(p);
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    // ── Worktrees ─────────────────────────────────────────────────────────

    /**
     * Reports agent worktrees under {@code .claude/worktrees} without touching
     * them. Each carries a full second copy of the frontend — including its
     * {@code node_modules} and built {@code dist/} — so a few abandoned ones
     * quietly cost hundreds of MB. They are only ever listed, never deleted:
     * one may hold uncommitted work, and the safe removal path goes through
     * git so the worktree registration is cleaned up too.
     */
    private static void reportWorktrees(Path root) {
        Path dir = root.resolve(".claude").resolve("worktrees");
        if (!Files.isDirectory(dir)) return;

        List<Path> trees;
        try (var list = Files.list(dir)) {
            trees = list.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return;
        }
        if (trees.isEmpty()) return;

        System.out.println("\nAgent worktrees (not removed - listed only)");
        System.out.println("-".repeat(88));
        for (Path t : trees) {
            String dirty;
            try {
                String status = run(List.of("git", "-C", t.toString(), "status", "--porcelain"), false);
                dirty = (status == null || status.isBlank())
                        ? "clean"
                        : status.strip().lines().count() + " uncommitted change(s)";
            } catch (Exception e) {
                dirty = "status unknown";
            }
            System.out.printf("  %10s  %-28s %s%n", human(sizeOf(t)), t.getFileName(), dirty);
        }
        System.out.println("""
                  Retire a finished one with:  git worktree remove <path>
                  (add --force only when you are sure the changes above are disposable)""");
    }

    // ── Docker ────────────────────────────────────────────────────────────

    private static void pruneDocker(boolean apply, boolean includeVolumes) throws Exception {
        System.out.println("\nDocker");
        System.out.println("-".repeat(88));
        run(List.of("docker", "system", "df"), true);

        if (!apply) {
            System.out.println("\nDry run - would run:");
            System.out.println("  docker builder prune -a -f      (build cache)");
            System.out.println("  docker image prune -f           (dangling images)");
            if (includeVolumes) System.out.println("  docker volume prune -f          (dangling volumes)");
            return;
        }

        System.out.println("\nPruning build cache...");
        run(List.of("docker", "builder", "prune", "-a", "-f"), true);
        System.out.println("Pruning dangling images...");
        run(List.of("docker", "image", "prune", "-f"), true);

        if (includeVolumes) {
            if (!stackIsRunning()) {
                System.out.println("REFUSING to prune volumes: no Orphos containers are running.");
                System.out.println("While the stack is down its Postgres/Qdrant/Keycloak volumes look");
                System.out.println("\"dangling\" and would be destroyed. Start the stack, then re-run.");
            } else {
                System.out.println("Pruning dangling volumes...");
                run(List.of("docker", "volume", "prune", "-f"), true);
            }
        }
    }

    /** True when at least one {@code eip-*} container is up. */
    private static boolean stackIsRunning() throws Exception {
        String out = run(List.of("docker", "ps", "--filter", "name=eip-", "--format", "{{.Names}}"), false);
        return out != null && !out.isBlank();
    }

    private static String run(List<String> command, boolean echo) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        if (echo && !out.isBlank()) System.out.println(out.strip().indent(2).stripTrailing());
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static long sizeOf(Path path) {
        if (!Files.isDirectory(path)) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0;
            }
        }
        final long[] total = {0};
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A partial total is still useful for the report; nothing to recover here.
        }
        return total[0];
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }

    private static String value(List<String> argv, String name, String fallback) {
        for (String a : argv) {
            if (a.startsWith(name + "=")) return a.substring(name.length() + 1);
        }
        return fallback;
    }

    /**
     * Refuses to run anywhere that is not an Orphos checkout. A recursive
     * deleter pointed at the wrong directory by a stray {@code --root} is the
     * one failure mode of this script that cannot be undone.
     */
    private static void requireOrphosCheckout(Path root) {
        boolean looksRight = Files.exists(root.resolve("pom.xml"))
                && Files.exists(root.resolve("docker-compose.app.yml"));
        if (!looksRight) {
            System.err.println(root + " does not look like an Orphos checkout");
            System.err.println("(expected pom.xml and docker-compose.app.yml side by side).");
            System.err.println("Refusing to run. Pass --root=PATH to point at the checkout.");
            System.exit(2);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Reclaims regenerable build output from an Orphos checkout.

                  java scripts/maintenance/OrphosCleanup.java [options]

                  --apply                Delete (default is a dry run that changes nothing)
                  --root=PATH            Checkout to clean (default: current directory)
                  --older-than-days=N    Only touch items untouched for N days
                  --node                 Also delete node_modules/
                  --docker               Also prune Docker build cache + dangling images
                  --docker-volumes       Also prune dangling volumes (needs --docker; stack must be up)
                  --keep=build,dist,...  Skip categories: build, dist, logs, backups, temp
                  --help                 This message

                Never removed: .git/, .env, orphos.config.json, config/fuseki/*.tar.gz""");
    }
}
