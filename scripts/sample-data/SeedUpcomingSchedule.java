import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds a demo schedule into ArcadeDB's {@code MailInsight} document type: today's
 * meetings, the meetings for the next ten days, and the trips in that period.
 *
 * <p>Why this exists: the sampled calendar rows shipped in
 * {@code orphos-sample-data.sql} have FIXED dates (Aug 2026). The dashboard's
 * calendar only shows the viewer's own day, so those rows drop out of range as
 * soon as the demo runs on any other date. This seeder writes the same kind of
 * row relative to TODAY, so it can be re-run on whatever day the demo is given.</p>
 *
 * <p><b>One source for both surfaces.</b> The web dashboard and the mobile app
 * read the same two endpoints: {@code /api/mail-insight/meetings} for meetings
 * and {@code /api/mobile/travel} for trips. Neither endpoint has a sample
 * fallback, so the data seeded here is the only data either surface shows. Trips
 * are not written to the {@code Travel} type directly. That type is owner-scoped,
 * so a row needs the user's Keycloak {@code sub}, which this script does not
 * know. Instead each trip is seeded as a calendar entry with a route
 * ("Bangalore to Delhi") and a mode word ("Flight"). The mobile BFF's
 * {@code TravelService} infers the trip from that entry for whichever user asks
 * first. This is the same path a real booking email takes.</p>
 *
 * <p>Run (JDK 17+, single-file source launch, no compile step):</p>
 * <pre>
 *   java SeedUpcomingSchedule.java &lt;tenantId&gt; [database]
 * </pre>
 * The default database is {@code orphos}. The connection is read from the
 * environment variables {@code ARCADEDB_URL}, {@code ARCADEDB_USER} and
 * {@code ARCADEDB_PASSWORD}. The services use the same variables, so a shell
 * that has sourced {@code .env} needs no further arguments.
 *
 * <p>Idempotent: every row carries a {@link #DEDUPE_PREFIX} dedupe key, and each
 * run DELETEs that set before inserting. Re-running re-dates the schedule rather
 * than adding a second copy. This matters because {@code MailInsight} has a
 * UNIQUE index on (tenantId, dedupeKey), so a blind re-insert would fail the
 * whole batch. The run also clears the {@code Travel} rows inferred from the
 * previous run's entries. {@code TravelService} never rewrites a trip it has
 * already stored, so a leftover row would keep the old date.</p>
 */
public final class SeedUpcomingSchedule {

    /** Marks the rows this seeder owns, so a re-run can clear exactly its own. */
    private static final String DEDUPE_PREFIX = "demo-schedule-";

    /**
     * Prefixes used by earlier seeders, which a run also clears:
     * {@code SeedTodaysMeetings} (this script's predecessor) and
     * {@code TravelService}'s old built-in demo trips. Those trips had year-less
     * dates ("Mon, 22 Sep"), so they fell behind the calendar and disagreed with
     * the seeded schedule.
     */
    private static final String LEGACY_MEETING_PREFIX = "demo-today-meeting-";
    private static final String LEGACY_TRIP_PREFIX = "demo-trip-";

    /**
     * `MEETING`, which is neither `TASK` nor `NOTIFICATION`.
     *
     * <p>Those two are what the Tasks for Action and Notifications tabs query on
     * ({@code MailInsightRepository.listTasks/listNotifications}), while
     * {@code listMeetings} filters on {@code sourceType} alone and ignores kind.
     * A calendar entry is not an action item, so giving these rows either of the
     * queue kinds would have posted every meeting into a work queue as well.</p>
     */
    private static final String KIND = "MEETING";

    /**
     * One entry in the seeded schedule.
     *
     * <p>Wording matters. {@code TravelService} treats an entry as a trip when its
     * title, agenda or location contains a travel keyword ("travel", "flight",
     * "train", "check-in", "departure", and a few more) or an "X to Y" route.
     * It matches substrings, so "training" and "constraint" also count. Meeting
     * text must avoid all of these, and every trip must contain both a route and
     * a mode word.</p>
     *
     * @param dayOffset    days from today (0 = today)
     * @param startMinutes minutes from local midnight, or -1 for the relative
     *                     slot (see {@link #NEXT_MEETING_LEAD_MINUTES})
     */
    private record Slot(int dayOffset, int startMinutes, int durationMinutes, String title,
                        String organiser, String agenda, String location, int attendees,
                        List<String> brief) { }

    /**
     * How far ahead the one relative meeting is placed.
     *
     * <p>The fixed slots are wall-clock times and may all be past by the time the
     * demo is shown. The next-meeting panel would then say the day is over. One
     * slot is therefore placed relative to the run time so there is always a
     * meeting ahead. 45 minutes is close enough to read as imminent without being
     * about to start.</p>
     */
    private static final int NEXT_MEETING_LEAD_MINUTES = 45;

    private static final String TRAVEL_DESK = "Travel desk";

    private static final List<Slot> SLOTS = List.of(
            // ── Today ──
            new Slot(0, hm(9, 15), 30, "Daily engineering standup", "Ravi Menon",
                    "Blockers on the ingest pipeline", "Huddle room 2", 8,
                    List.of("Ingest pipeline blocked on the schema migration",
                            "Two P1 bugs carried over from yesterday")),
            new Slot(0, hm(11, 0), 45, "Northwind renewal - contract walkthrough", "Anita Desai",
                    "Auto-renew clause and the escalation path", "Teams", 5,
                    List.of("Auto-renew clause needs legal sign-off",
                            "Agree the escalation path before the renewal date")),
            // The relative slot, which is always ahead. See NEXT_MEETING_LEAD_MINUTES.
            new Slot(0, -1, 30, "Cash position review", "Priya Raghavan",
                    "Two overdue collections against the Q2 gap", "Teams", 3,
                    List.of("Two collections are overdue against the Q2 gap",
                            "Decide whether to draw on the credit line")),
            new Slot(0, hm(14, 0), 45, "Meridian scope change - client call", "Daniel Okafor",
                    "Scope decision blocking the Q3 re-plan", "Teams", 6,
                    List.of("Client wants two modules pulled into Q3",
                            "The Q3 re-plan is blocked on this decision")),
            new Slot(0, hm(16, 30), 45, "Board finance review", "Priya Raghavan",
                    "Q2 cash position", "Boardroom", 4,
                    List.of("Q2 cash position against plan",
                            "Capex request for the Mysuru plant line")),
            new Slot(0, hm(17, 45), 30, "Weekly leadership sync", "Kenji Tanaka",
                    "Close-out of the week and next week's priorities", "Teams", 7,
                    List.of("Close-out of the week's commitments",
                            "Next week's top three priorities")),

            // ── Coming days: meetings ──
            new Slot(1, hm(11, 0), 180, "Delhi leadership offsite - day one", "Kenji Tanaka",
                    "FY27 operating plan and regional targets", "Aerocity, New Delhi", 12,
                    List.of("FY27 operating plan walkthrough",
                            "Regional targets for the north zone")),
            new Slot(2, hm(13, 0), 90, "Jaipur investor roadshow", "Anita Desai",
                    "Series C follow-on with two family offices", "Rambagh Palace, Jaipur", 6,
                    List.of("Series C follow-on with two family offices",
                            "Bring the updated cohort retention numbers")),
            new Slot(3, hm(10, 0), 45, "Northwind QBR preparation", "Priya Raghavan",
                    "Renewal risk and the expansion ask", "Teams", 4,
                    List.of("Renewal risk flagged by the account team",
                            "Size the expansion ask before the QBR")),
            new Slot(4, hm(15, 0), 60, "Hiring committee for senior engineers", "Ravi Menon",
                    "Three final-round candidates", "Huddle room 2", 5,
                    List.of("Three final-round candidates to decide on",
                            "Offer bands were approved last week")),
            new Slot(6, hm(14, 0), 60, "Mysuru plant quality review", "Daniel Okafor",
                    "Line 3 defect rate and the corrective actions", "Plant office, Mysuru", 8,
                    List.of("Line 3 defect rate is up for a second month",
                            "Review the corrective actions and their owners")),
            new Slot(7, hm(16, 0), 60, "Q3 board pre-read review", "Priya Raghavan",
                    "Cash runway and the capex request", "Boardroom", 4,
                    List.of("Cash runway under the base and downside cases",
                            "Capex request wording for the board pack")),
            new Slot(10, hm(10, 0), 120, "APAC partner summit", "Kenji Tanaka",
                    "Joint go-to-market plan with regional partners", "Marina Bay Sands, Singapore", 20,
                    List.of("Joint go-to-market plan with regional partners",
                            "Partner incentive model for FY27")),

            // ── Coming days: trips (route + mode word, see Slot) ──
            new Slot(1, hm(6, 30), 165, "Board offsite travel", TRAVEL_DESK,
                    "Flight Bangalore to Delhi, 6E 2134 from Terminal 1", "Kempegowda Intl Airport", 1,
                    List.of("Flight 6E 2134 from Terminal 1", "Hotel booked at Aerocity")),
            new Slot(2, hm(8, 10), 270, "Investor roadshow travel", TRAVEL_DESK,
                    "Train Delhi to Jaipur, Shatabdi coach C2", "New Delhi station", 1,
                    List.of("Shatabdi, coach C2", "Car to the venue on arrival")),
            new Slot(3, hm(19, 45), 150, "Return travel", TRAVEL_DESK,
                    "Flight Jaipur to Bangalore, AI 815", "Jaipur Airport", 1,
                    List.of("Flight AI 815", "Web check-in opens 48 hours before")),
            new Slot(6, hm(7, 0), 180, "Plant visit travel", TRAVEL_DESK,
                    "Cab Bangalore to Mysuru, pickup at home", "Home pickup", 1,
                    List.of("Cab pickup at 7:00 AM", "Return the same evening")),
            new Slot(9, hm(23, 55), 330, "APAC summit travel", TRAVEL_DESK,
                    "Flight Bangalore to Singapore, SQ 511", "Kempegowda Intl Airport", 1,
                    List.of("Flight SQ 511, overnight", "Visa and forex are sorted")));

    private static int hm(int hour, int minute) { return hour * 60 + minute; }

    private static String url, user, pass, database;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java SeedUpcomingSchedule.java <tenantId> [database]");
            System.exit(2);
        }
        String tenantId = args[0];
        database = args.length > 1 ? args[1] : "orphos";

        url = env("ARCADEDB_URL");
        user = env("ARCADEDB_USER");
        pass = env("ARCADEDB_PASSWORD");

        // The local zone is used on purpose. Both clients show the calendar for
        // THEIR OWN midnight-to-midnight day (meetings.js todayWindow on the web,
        // OrphosRepository.dayStartIso on mobile). A row seeded in UTC would land
        // on the wrong day for anyone east or west of UTC.
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime midnight = LocalDate.now(zone).atStartOfDay(zone);
        ZonedDateTime now = ZonedDateTime.now(zone);

        int trips = clearInferredTravel(tenantId);
        int cleared = deletePreviousRun(tenantId);
        System.out.printf("cleared %d meeting(s) and %d inferred trip(s) from a previous run%n",
                cleared, trips);

        int n = 0;
        for (Slot slot : SLOTS) {
            ZonedDateTime start = slot.startMinutes() < 0
                    ? now.plusMinutes(NEXT_MEETING_LEAD_MINUTES).truncatedTo(ChronoUnit.MINUTES)
                    : midnight.plusDays(slot.dayOffset()).plusMinutes(slot.startMinutes());
            ZonedDateTime end = start.plusMinutes(slot.durationMinutes());
            insert(tenantId, DEDUPE_PREFIX + (++n), slot, start, end);
            System.out.printf("  %s %s  %s%s%n",
                    start.toLocalDate(), start.toLocalTime().withSecond(0), slot.title(),
                    TRAVEL_DESK.equals(slot.organiser()) ? "  [trip]" : "");
        }
        System.out.printf("seeded %d entr%s from %s into %s%n",
                n, n == 1 ? "y" : "ies", midnight.toLocalDate(), database);
        System.out.println("trips appear on the next GET /api/mobile/travel, on the web and mobile alike");
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("missing environment variable: " + key);
            System.exit(2);
        }
        return v;
    }

    /** The SQL predicate that selects every meeting row this seeder, or its predecessor, owns. */
    private static String ownRows(String tenantId) {
        return "tenantId = '" + esc(tenantId) + "' AND (dedupeKey LIKE '" + DEDUPE_PREFIX
                + "%' OR dedupeKey LIKE '" + LEGACY_MEETING_PREFIX + "%')";
    }

    /**
     * Deletes the {@code Travel} rows inferred from this seeder's previous entries,
     * plus the old built-in demo trips, for every user in the tenant.
     *
     * <p>{@code TravelService.infer} stores {@code sourceRef} as the id of the
     * meeting the trip came from. The ids are therefore read BEFORE those
     * meetings are deleted. The {@code Travel} type belongs to the mobile BFF and
     * is missing until that service has booted once. In that case there is
     * nothing to clear, and the error is not fatal.</p>
     */
    private static int clearInferredTravel(String tenantId) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(command("SELECT id FROM MailInsight WHERE " + ownRows(tenantId)));
        while (m.find()) ids.add(m.group(1));

        List<String> matches = new ArrayList<>();
        for (String id : ids) matches.add("payload LIKE '%\"sourceRef\":\"" + esc(id) + "\"%'");
        matches.add("payload LIKE '%\"sourceId\":\"" + LEGACY_TRIP_PREFIX + "%'");

        try {
            return count(command("DELETE FROM Travel WHERE tenantId = '" + esc(tenantId)
                    + "' AND (" + String.join(" OR ", matches) + ")"));
        } catch (IllegalStateException e) {
            System.out.println("Travel type not present yet (the mobile BFF has not booted); nothing to clear");
            return 0;
        }
    }

    /** Removes this seeder's own meeting rows, so a re-run re-dates them rather than duplicating them. */
    private static int deletePreviousRun(String tenantId) throws Exception {
        return count(command("DELETE FROM MailInsight WHERE " + ownRows(tenantId)));
    }

    /** ArcadeDB returns a DELETE's row count as the single result row. */
    private static int count(String body) {
        int at = body.indexOf("\"count\":");
        if (at < 0) return 0;
        int end = at + 8;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        try {
            return Integer.parseInt(body.substring(at + 8, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void insert(String tenantId, String dedupeKey, Slot slot,
                               ZonedDateTime start, ZonedDateTime end) throws Exception {
        String startIso = start.toInstant().toString();
        String endIso = end.toInstant().toString();
        String nowIso = java.time.Instant.now().toString();
        // The id is stable across runs, so a trip inferred from this entry keeps
        // one identity from one run to the next.
        String id = UUID.nameUUIDFromBytes((tenantId + ":" + dedupeKey)
                .getBytes(StandardCharsets.UTF_8)).toString();

        // dueDate is the field the web calendar reads as the start (see
        // MailInsightRepository.startOf: dueDate, else receivedAt). meetingStartAt
        // holds the same instant for the MeetingDetail the mobile card and
        // TravelService read.
        String sql = "INSERT INTO MailInsight SET "
                + "id = '" + id + "', "
                + "tenantId = '" + esc(tenantId) + "', "
                + "mailboxId = 'demo', "
                // Blank ownerSub = tenant-wide, so every demo login sees the schedule.
                + "ownerSub = '', "
                + "kind = '" + KIND + "', "
                + "source = 'EMAIL', "
                + "sourceType = 'MEETING', "
                + "sourceRef = '" + esc(dedupeKey) + "', "
                + "dedupeKey = '" + esc(dedupeKey) + "', "
                + "title = '" + esc(slot.title()) + "', "
                + "summary = '" + esc(slot.agenda()) + "', "
                + "senderDisplay = '" + esc(slot.organiser()) + "', "
                + "organizerDisplay = '" + esc(slot.organiser()) + "', "
                + "priority = 'MEDIUM', "
                + "criticality = 'INFORMATION', "
                + "score = 40, "
                + "rationale = 'Seeded demo calendar entry', "
                + "dueDate = '" + startIso + "', "
                + "receivedAt = '" + startIso + "', "
                + "status = 'OPEN', "
                + "inferenceSource = 'RULES', "
                + "meetingStartAt = '" + startIso + "', "
                + "meetingEndAt = '" + endIso + "', "
                + "attendeeCount = " + slot.attendees() + ", "
                + "locationText = '" + esc(slot.location()) + "', "
                + "briefPointsJson = '" + esc(jsonArray(slot.brief())) + "', "
                + "createdAt = '" + nowIso + "', "
                + "updatedAt = '" + nowIso + "'";
        command(sql);
    }

    /** ["a","b"], the shape of MailInsight.briefPointsJson. */
    private static String jsonArray(List<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String s : items) quoted.add("\"" + jsonEsc(s) + "\"");
        return "[" + String.join(",", quoted) + "]";
    }

    /**
     * POSTs one SQL command and returns the raw response body. Throws on non-2xx.
     *
     * <p>This uses {@code HttpURLConnection} rather than the newer
     * {@code java.net.http} client. The newer client opens an NIO selector, which
     * needs a loopback socket pair. Some sandboxes this script runs in do not
     * provide one, and the client failed while being constructed, before it ever
     * reached the network. The older API has no such dependency, and the seeder
     * only ever needs one blocking POST at a time.</p>
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
        // On a non-2xx the body arrives on the error stream, and that is where
        // ArcadeDB puts the reason. Reading only getInputStream() here would
        // throw and lose the one useful part of the failure.
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

    /** Minimal JSON string escaping. */
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
