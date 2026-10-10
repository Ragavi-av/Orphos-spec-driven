package io.orphos.eip.mailinsight.pipeline;

import io.orphos.eip.mailinsight.graph.GraphItem;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Canned mail/calendar fixtures used by the {@code mode=sample} scan path so the
 * full normalize → classify → persist → render pipeline can be demonstrated
 * end-to-end without a real Microsoft 365 app registration.
 *
 * All names/contents here are obviously synthetic — no real personal data.
 */
public final class SampleFixtures {

    private SampleFixtures() {}

    public static List<GraphItem> items() {
        Instant now = Instant.now();
        return List.of(
                new GraphItem("sample-001", "EMAIL",
                        "URGENT: Production outage in EU region — action required",
                        "The EU payment cluster is down. Please approve the failover plan ASAP.",
                        "Ops Center", now.minus(2, ChronoUnit.HOURS),
                        true, true, null),
                new GraphItem("sample-002", "EMAIL",
                        "Please approve Q3 marketing budget by Friday",
                        "Awaiting your sign-off on the attached Q3 budget. Deadline by Friday EOD.",
                        "Finance Planning", now.minus(1, ChronoUnit.DAYS),
                        true, true, null),
                new GraphItem("sample-003", "EMAIL",
                        "Reminder: board deck review still pending",
                        "Friendly reminder that the board deck review is overdue. Please respond.",
                        "Executive Office", now.minus(3, ChronoUnit.DAYS),
                        false, true, null),
                new GraphItem("sample-004", "EMAIL",
                        "Weekly engineering newsletter",
                        "Highlights from the platform team this week. No action needed.",
                        "Engineering Comms", now.minus(4, ChronoUnit.DAYS),
                        false, false, null),
                new GraphItem("sample-005", "MEETING",
                        "Quarterly Business Review with leadership",
                        "QBR covering revenue, OKRs, and risk. Please review pre-read beforehand.",
                        "Chief of Staff", now.plus(2, ChronoUnit.DAYS),
                        false, true, now.plus(2, ChronoUnit.DAYS)),
                new GraphItem("sample-006", "EMAIL",
                        "Security advisory: rotate API credentials",
                        "A critical advisory recommends rotating credentials immediately.",
                        "Security Team", now.minus(6, ChronoUnit.HOURS),
                        true, true, null),

                // ── Today's calendar ────────────────────────────────────────────
                // The Upcoming Meetings widget counts calendar items starting inside
                // the viewer's current day. The only pre-existing meeting fixture
                // (sample-005) sits two days out, so without these the widget reads
                // zero on a demo tenant with no mailbox connected. Offsets are
                // relative to `now` so they stay inside today whenever the sample
                // scan runs; the last one is deliberately near-term so the "next
                // meeting" line has something imminent to show.
                new GraphItem("sample-007", "MEETING",
                        "Board pre-read walkthrough",
                        "Walk the board through Q2 revenue, cash position and the two open risks. "
                                + "Pre-read circulated Monday; expect questions on pipeline coverage.",
                        "Chief of Staff", now.plus(45, ChronoUnit.MINUTES),
                        false, true, now.plus(45, ChronoUnit.MINUTES)),
                new GraphItem("sample-008", "MEETING",
                        "Vendor contract renewal — final terms",
                        "Procurement needs a decision on the renewal. Legal flagged the liability cap; "
                                + "finance wants the three-year option priced before signature.",
                        "Procurement", now.plus(3, ChronoUnit.HOURS),
                        true, true, now.plus(3, ChronoUnit.HOURS)),
                new GraphItem("sample-009", "MEETING",
                        "Weekly leadership sync",
                        "Standing leadership review: delivery status, headcount requests and escalations "
                                + "from the regional teams.",
                        "Executive Office", now.plus(6, ChronoUnit.HOURS),
                        false, true, now.plus(6, ChronoUnit.HOURS)),
                new GraphItem("sample-010", "MEETING",
                        "1:1 with engineering manager",
                        "Regular check-in: sprint progress, blockers on the auth migration, and career "
                                + "development notes.",
                        "Priya Nair", now.plus(1, ChronoUnit.HOURS),
                        false, false, now.plus(1, ChronoUnit.HOURS)),
                new GraphItem("sample-011", "MEETING",
                        "Customer escalation — Acme Corp renewal at risk",
                        "Acme flagged pricing concerns ahead of their renewal. Need alignment on the "
                                + "discount ceiling before the call with their procurement lead.",
                        "Customer Success", now.plus(4, ChronoUnit.HOURS),
                        true, true, now.plus(4, ChronoUnit.HOURS)),
                new GraphItem("sample-012", "MEETING",
                        "Design review: onboarding flow v2",
                        "Walkthrough of the revised onboarding screens ahead of next sprint's build. "
                                + "Bring feedback on the empty-state copy.",
                        "Product Design", now.plus(7, ChronoUnit.HOURS),
                        false, false, now.plus(7, ChronoUnit.HOURS))
        );
    }
}
