import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Imports {@code orphos-sample-data.sql} into a running ArcadeDB over the HTTP
 * API, substituting the {@code __TENANT_ID__} token and posting the script in
 * chunks (language=sqlscript) so a large file never exceeds request limits.
 *
 * <p>Usage (JDK 11+, single-file source launch):</p>
 * <pre>
 *   java ImportSampleData.java &lt;tenantId&gt; [sqlFile] [host] [db] [chunkSize]
 * </pre>
 * Defaults: {@code sqlFile=orphos-sample-data.sql}, {@code host=http://localhost:2480},
 * {@code db=orphos}, {@code chunkSize=250} statements.
 *
 * <p>The ArcadeDB root password is read from {@code ARCADEDB_PASSWORD} in the
 * repo {@code .env} (two levels up), falling back to the env var of the same
 * name — it is never hardcoded and never printed.</p>
 */
public final class ImportSampleData {

    private static final String TENANT_TOKEN = "__TENANT_ID__";

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: java ImportSampleData.java <tenantId> "
                    + "[sqlFile] [host] [db] [chunkSize]");
            System.exit(2);
        }
        String tenantId = args[0];
        Path sqlFile = Path.of(args.length > 1 ? args[1] : "orphos-sample-data.sql");
        String host   = args.length > 2 ? args[2] : "http://localhost:2480";
        String db     = args.length > 3 ? args[3] : "orphos";
        int chunkSize = args.length > 4 ? Integer.parseInt(args[4]) : 250;

        String password = resolvePassword();
        if (password == null || password.isBlank()) {
            System.err.println("ARCADEDB_PASSWORD not found in ../../.env or environment.");
            System.exit(3);
        }

        String script = Files.readString(sqlFile).replace(TENANT_TOKEN, tenantId);
        List<String> statements = splitStatements(script);
        System.out.printf("Loaded %s → %,d statements. Importing into %s/%s (chunks of %d)…%n",
                sqlFile.getFileName(), statements.size(), host, db, chunkSize);

        HttpClient http = HttpClient.newHttpClient();
        String auth = "Basic " + Base64.getEncoder()
                .encodeToString(("root:" + password).getBytes(StandardCharsets.UTF_8));
        String url = host + "/api/v1/command/" + db;

        int done = 0, chunkNo = 0;
        for (int i = 0; i < statements.size(); i += chunkSize) {
            List<String> chunk = statements.subList(i, Math.min(i + chunkSize, statements.size()));
            String command = String.join(";\n", chunk) + ";";
            String body = "{\"language\":\"sqlscript\",\"command\":\"" + jsonEscape(command) + "\"}";

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            chunkNo++;
            if (resp.statusCode() / 100 != 2) {
                System.err.printf("%nChunk %d FAILED (HTTP %d): %s%n",
                        chunkNo, resp.statusCode(), truncate(resp.body(), 500));
                System.exit(4);
            }
            done += chunk.size();
            System.out.printf("\r  chunk %d — %,d / %,d statements", chunkNo, done, statements.size());
        }
        System.out.printf("%nDone. Imported %,d statements for tenant %s.%n", done, tenantId);
    }

    /** Splits on statement-terminating semicolons, dropping comments/blanks. */
    private static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        for (String raw : script.split(";\\s*\\n")) {
            StringBuilder cleaned = new StringBuilder();
            for (String line : raw.split("\\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("--")) continue;
                cleaned.append(t).append(' ');
            }
            String stmt = cleaned.toString().strip();
            if (!stmt.isEmpty()) out.add(stmt);
        }
        return out;
    }

    private static String resolvePassword() throws IOException {
        Path env = Path.of("..", "..", ".env").normalize();
        if (Files.exists(env)) {
            for (String line : Files.readAllLines(env)) {
                String t = line.strip();
                if (t.startsWith("ARCADEDB_PASSWORD=")) {
                    String v = t.substring("ARCADEDB_PASSWORD=".length()).strip();
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return System.getenv("ARCADEDB_PASSWORD");
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
