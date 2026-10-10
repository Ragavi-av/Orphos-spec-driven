package io.orphos.eip.setup;

import io.orphos.eip.setup.web.BrowserLauncher;
import io.orphos.eip.setup.web.FallbackHttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.channels.Selector;

/**
 * Standalone Orphos setup wizard.
 *
 * <p>Runs before the platform is installed: it serves a step-by-step wizard UI,
 * validates any external databases live, and writes a validated {@code .env} +
 * {@code orphos.config.json} that the layered Compose bring-up then consumes.
 * It intentionally has no dependency on the rest of the reactor so it can run
 * with nothing else up.</p>
 *
 * <h2>Two web stacks, chosen at startup</h2>
 * <p>Normally this is an ordinary Spring Boot web app on embedded Tomcat. But
 * Tomcat — like Jetty, Undertow and the JDK's own {@code com.sun.net.httpserver}
 * — is built on NIO, and NIO needs a {@link Selector}. Some Windows hosts refuse
 * the internal socket pair a selector requires, so {@code Selector.open()} throws
 * {@code Unable to establish loopback connection} even though ordinary blocking
 * sockets work perfectly. On such a machine every servlet container fails to
 * start, Spring aborts, and because the packaged launcher is a windowed app with
 * no console the operator sees only a bare <i>"Failed to launch JVM"</i> dialog.
 *
 * <p>So the stack is probed for, not assumed. If a selector can be created, we
 * run Tomcat exactly as before. If it cannot, we start Spring with no web server
 * and serve the same UI and the same controller through
 * {@link FallbackHttpServer}, which uses blocking sockets only. The operator
 * double-clicks the exe and it works either way; the only visible difference is a
 * line in the log saying which path was taken.</p>
 */
@SpringBootApplication
public class SetupWizardApplication {

    private static final Logger log = LogManager.getLogger(SetupWizardApplication.class);

    public static void main(String[] args) throws Exception {
        // Already running? Double-clicking the exe a second time (or leaving an
        // earlier copy open) would otherwise fail to bind the port, abort startup,
        // and — because the packaged launcher is windowed with no console — show a
        // bare "Failed to launch JVM" dialog that says nothing about the real
        // cause. Re-open the running instance instead: that is what the operator
        // wanted by double-clicking anyway.
        int port = configuredPort();
        if (somethingListeningOn(port)) {
            String url = "http://localhost:" + port;
            log.info("The setup wizard is already running at {} - opening it rather than "
                    + "starting a second copy.", url);
            BrowserLauncher.openIfEnabled(url);
            return;
        }

        if (selectorAvailable()) {
            new SpringApplicationBuilder(SetupWizardApplication.class).run(args);
            return;
        }

        log.warn("This host cannot create an NIO selector, so no servlet container can start here. "
                + "Falling back to a blocking-socket HTTP server — the wizard behaves identically.");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SetupWizardApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        ctx.getBean(FallbackHttpServer.class).serve(resolvePort(ctx));
    }

    /**
     * The wizard port, resolved before Spring starts so the already-running check
     * can happen first. Mirrors {@code server.port: ${ORPHOS_SETUP_PORT:8099}} in
     * application.yml — the same variable an operator would set.
     */
    private static int configuredPort() {
        String v = System.getenv("ORPHOS_SETUP_PORT");
        if (v == null || v.isBlank()) v = System.getProperty("server.port");
        if (v == null || v.isBlank()) return 8099;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 8099;
        }
    }

    /**
     * Whether anything is already accepting connections on {@code port}. The port
     * is app-specific, so a listener there is taken to be an earlier copy of the
     * wizard. A short timeout keeps startup snappy when the port is genuinely free.
     */
    private static boolean somethingListeningOn(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether {@code Selector.open()} works on this host. Cheap, and the only
     * reliable test — the failure is environmental (a blocked loopback socket
     * pair), not something a version check or an OS check can predict.
     */
    private static boolean selectorAvailable() {
        try (Selector s = Selector.open()) {
            return s.isOpen();
        } catch (Throwable t) {
            log.debug("Selector.open() failed: {}", t.toString());
            return false;
        }
    }

    /**
     * The configured wizard port. Read from the same {@code server.port} property
     * Tomcat would have used, so {@code ORPHOS_SETUP_PORT} keeps working in both
     * modes.
     */
    private static int resolvePort(ConfigurableApplicationContext ctx) {
        String configured = ctx.getEnvironment().getProperty("server.port", "8099");
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable server.port='{}' — using 8099.", configured);
            return 8099;
        }
    }
}
