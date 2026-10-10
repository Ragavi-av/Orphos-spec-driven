import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Creates the {@code FxRate} type and seeds a demo INR↔JPY rate table.
 *
 * <p>Why this exists: the dashboard can restate a figure in the currency the
 * reader picked (see {@code io.orphos.eip.common.money}), but only where a rate
 * covers the observation's period. With no rates a Japanese reader sees the
 * board's real INR figures — correct, and exactly what the seeded Zenitus data
 * declares, but not what a demo of the feature needs.</p>
 *
 * <h2>These are illustrative rates, not market quotes</h2>
 * Every row is stamped {@code source = "}{@value #SOURCE}{@code "}, and that
 * string is what the SPA shows in the conversion tooltip. It says so in the UI
 * because a reader has no other way to tell a demo rate from a treasury one, and
 * a figure derived from an invented rate that presents itself as real is worse
 * than no conversion at all. The tenant's own rates replace these through the
 * admin API ({@code /api/admin/fx-rates}); this seeder only fills an empty table.
 *
 * <p>The periods come from {@code zenitus-governance-profile.json}, whose
 * observations run FY2027 Q1–Q2 and the 2026 monthly cash series. One AVERAGE
 * and one CLOSING rate is written per period start, because a flow and a balance
 * in the same period convert at different rates — see {@code FigureKind}.</p>
 *
 * <p>Run (JDK 11+, single-file source launch — no compile step):</p>
 * <pre>
 *   java SeedFxRates.java &lt;tenantId&gt; [database]
 * </pre>
 * Defaults: database {@code orphos}. Connection is read from the environment —
 * {@code ARCADEDB_URL}, {@code ARCADEDB_USER}, {@code ARCADEDB_PASSWORD} — the
 * same variables the services use.
 *
 * <p>Idempotent: the run DELETEs this tenant's rows carrying {@link #SOURCE}
 * before inserting, so re-running replaces the demo table without touching rates
 * an admin entered by hand.</p>
 */
public final class SeedFxRates {

    /** Stamped on every seeded row: the provenance line the tooltip shows. */
    private static final String SOURCE = "Seeded demo rate — not a market quote";

    /**
     * One rate row.
     *
     * @param rate units of {@code to} per ONE unit of {@code from}
     */
    private record Rate(String from, String to, String rate, String type, String effectiveFrom) {}

    /**
     * Illustrative INR→JPY rates, drifting slightly across the periods so the
     * demo shows what the feature is for: a closed period keeps the rate it was
     * priced at, and a later period prices differently. A single flat rate would
     * make the as-of date in the tooltip look decorative.
     *
     * <p>AVERAGE sits below CLOSING in each period only to keep the two visibly
     * distinct on screen — that ordering carries no meaning.</p>
     */
    private static final List<Rate> RATES = List.of(
            // FY2027 Q1 (Apr–Jun 2026)
            new Rate("INR", "JPY", "1.720", "AVERAGE", "2026-04-01"),
            new Rate("INR", "JPY", "1.735", "CLOSING", "2026-04-01"),
            // Monthly cash series
            new Rate("INR", "JPY", "1.745", "AVERAGE", "2026-07-01"),
            new Rate("INR", "JPY", "1.760", "CLOSING", "2026-07-01"),
            new Rate("INR", "JPY", "1.755", "AVERAGE", "2026-08-01"),
            new Rate("INR", "JPY", "1.775", "CLOSING", "2026-08-01"),
            // FY2027 Q2 (Jul–Sep 2026)
            new Rate("INR", "JPY", "1.750", "AVERAGE", "2026-09-01"),
            new Rate("INR", "JPY", "1.780", "CLOSING", "2026-09-01"),

            // The reverse direction, so a JPY-reporting entity's figures can be
            // read in rupees too. Reciprocals of the rates above, not independent
            // quotes — the converter never derives one direction from the other,
            // so both have to exist as rows.
            new Rate("JPY", "INR", "0.5814", "AVERAGE", "2026-04-01"),
            new Rate("JPY", "INR", "0.5764", "CLOSING", "2026-04-01"),
            new Rate("JPY", "INR", "0.5731", "AVERAGE", "2026-07-01"),
            new Rate("JPY", "INR", "0.5682", "CLOSING", "2026-07-01"),
            new Rate("JPY", "INR", "0.5698", "AVERAGE", "2026-08-01"),
            new Rate("JPY", "INR", "0.5634", "CLOSING", "2026-08-01"),
            new Rate("JPY", "INR", "0.5714", "AVERAGE", "2026-09-01"),
            new Rate("JPY", "INR", "0.5618", "CLOSING", "2026-09-01"));

    private static String url;
    private static String user;
    private static String pass;
    private static String database = "orphos";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java SeedFxRates.java <tenantId> [database]");
            System.exit(2);
        }
        String tenantId = args[0];
        if (args.length > 1) database = args[1];

        url = envOr("ARCADEDB_URL", "http://localhost:2480");
        user = envOr("ARCADEDB_USER", "root");
        pass = System.getenv("ARCADEDB_PASSWORD");
        if (pass == null || pass.isBlank()) {
            System.err.println("ARCADEDB_PASSWORD not set in the environment.");
            System.exit(2);
        }

        ensureSchema();

        // Clear only what this seeder owns, so hand-entered rates survive a re-run.
        command("DELETE FROM FxRate WHERE tenantId = '" + esc(tenantId)
                + "' AND source = '" + esc(SOURCE) + "'");

        int written = 0;
        for (Rate r : RATES) {
            command("INSERT INTO FxRate SET "
                    + "id = '" + UUID.randomUUID() + "', "
                    + "tenantId = '" + esc(tenantId) + "', "
                    + "fromCurrency = '" + esc(r.from()) + "', "
                    + "toCurrency = '" + esc(r.to()) + "', "
                    // Stored as a string: the lookup compares dates
                    // lexicographically (valid for ISO-8601) and the rate is read
                    // back through BigDecimal, so neither wants a float.
                    + "rate = '" + esc(r.rate()) + "', "
                    + "rateType = '" + esc(r.type()) + "', "
                    + "effectiveFrom = '" + esc(r.effectiveFrom()) + "', "
                    + "source = '" + esc(SOURCE) + "'");
            written++;
        }
        System.out.println("Seeded " + written + " FX rates for tenant " + tenantId
                + " into " + database + ".");
        System.out.println("These are illustrative rates. Replace them with the tenant's own "
                + "in Administration → Currencies & FX.");
    }

    private static void ensureSchema() throws Exception {
        command("CREATE VERTEX TYPE FxRate IF NOT EXISTS");
        // CREATE PROPERTY has no IF NOT EXISTS in ArcadeDB, so a re-run reports
        // "already exists" for each of these — tolerated, same as the ontology loader.
        for (String prop : List.of("tenantId", "fromCurrency", "toCurrency", "rate",
                "rateType", "effectiveFrom", "source")) {
            try {
                command("CREATE PROPERTY FxRate." + prop + " STRING");
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("already exists")) throw e;
            }
        }
        // The lookup filters on the pair + type and orders by date, so this is the
        // index it actually walks. NOTUNIQUE: superseding a rate means adding a
        // later row, and a unique key here would forbid exactly that.
        command("CREATE INDEX IF NOT EXISTS ON FxRate "
                + "(tenantId, fromCurrency, toCurrency, rateType, effectiveFrom) NOTUNIQUE");
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * POSTs one SQL command and returns the raw response body. Throws on non-2xx.
     *
     * <p>{@code HttpURLConnection} rather than {@code java.net.http}: the newer
     * client opens an NIO selector needing a loopback socket pair, which is not
     * available in every sandbox these scripts run from. Same choice, same
     * reason, as {@code SeedUpcomingSchedule}.</p>
     */
    private static String command(String sql) throws Exception {
        String payload = "{\"language\":\"sql\",\"command\":\"" + jsonEsc(sql) + "\"}";
        String auth = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection)
                URI.create(url + "/api/v1/command/" + database).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Basic " + auth);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream src = status / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
        String body = src == null ? "" : readAll(src);
        conn.disconnect();

        if (status / 100 != 2) {
            throw new IllegalStateException("ArcadeDB " + status + ": " + body);
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    /** Single-quote escaping for SQL string literals. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Minimal JSON string escaping for the request envelope. */
    private static String jsonEsc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.toString();
    }
}
