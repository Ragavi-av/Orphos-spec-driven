package io.orphos.eip.agent.spec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What causes an agent to run (TO-BE design §5.2). A sealed hierarchy so the set of trigger
 * kinds is closed and exhaustively switchable by the orchestrator:
 *
 * <ul>
 *   <li>{@link Schedule} — a cron expression (e.g. the Briefing agent).</li>
 *   <li>{@link Event} — an event type on the bus (e.g. Email Triage, Decision Intelligence).</li>
 *   <li>{@link OnDemand} — invoked explicitly from the UI or another caller.</li>
 * </ul>
 *
 * <p>Polymorphic JSON carries a {@code kind} discriminator so the manifest round-trips over the
 * registry wire contract.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Trigger.Schedule.class, name = "SCHEDULE"),
        @JsonSubTypes.Type(value = Trigger.Event.class, name = "EVENT"),
        @JsonSubTypes.Type(value = Trigger.OnDemand.class, name = "ON_DEMAND")
})
public sealed interface Trigger {

    record Schedule(String cron) implements Trigger {}

    record Event(String eventType) implements Trigger {}

    record OnDemand() implements Trigger {}
}
