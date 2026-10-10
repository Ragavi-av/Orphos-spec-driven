package io.orphos.eip.setup.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens the operator's default browser on the wizard UI once the web server is
 * listening. This is what makes the packaged {@code Orphos Setup.exe} feel like a
 * double-clickable app rather than a bare server: the operator never has to know
 * the port or type a URL.
 *
 * <p>Best-effort and fail-safe — if no desktop/browser is available (a headless
 * server, an SSH session) it just logs the URL and the server keeps running. Set
 * {@code ORPHOS_SETUP_OPEN_BROWSER=false} to suppress it entirely (useful for
 * automated/CI launches).</p>
 */
@Component
public class BrowserLauncher implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LogManager.getLogger(BrowserLauncher.class);

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        log.info("Setup wizard ready at http://localhost:{}", port);
        openIfEnabled("http://localhost:" + port);
    }

    /**
     * Opens {@code url} unless suppressed, logging the outcome either way.
     *
     * <p>Public and static because the selector-less fallback server
     * ({@code FallbackHttpServer}) never raises {@link WebServerInitializedEvent}
     * — there is no Spring web server in that mode — yet it needs exactly this
     * behaviour. Sharing the method keeps one definition of "how the wizard opens
     * a browser" rather than two that can drift.</p>
     */
    public static void openIfEnabled(String url) {
        if (!openBrowserEnabled()) {
            log.info("Browser auto-open disabled (ORPHOS_SETUP_OPEN_BROWSER=false) - open {} yourself.", url);
            return;
        }
        if (openBrowser(url)) {
            log.info("Opened {} in your default browser.", url);
        } else {
            log.info("Could not open a browser automatically — open {} in a browser to continue.", url);
        }
    }

    private static boolean openBrowserEnabled() {
        String v = System.getenv("ORPHOS_SETUP_OPEN_BROWSER");
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    /**
     * Tries {@link Desktop} first, then falls back to the OS handler
     * ({@code cmd /c start} on Windows, {@code xdg-open} / {@code open} elsewhere)
     * for environments where AWT's Desktop is unavailable.
     */
    private static boolean openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to the OS-level handler below.
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                // Empty "" is the (unused) window title so a URL with spaces still works.
                pb = new ProcessBuilder("cmd", "/c", "start", "", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
