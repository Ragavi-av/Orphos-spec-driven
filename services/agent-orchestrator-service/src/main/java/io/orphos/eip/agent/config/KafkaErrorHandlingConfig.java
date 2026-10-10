package io.orphos.eip.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka-native retry/dead-lettering for {@code AgentEventListener}'s {@code eip.agent-events}
 * consumer (only active when {@code eip.orchestrator.kafka-enabled=true}).
 *
 * <p>Spring Boot's autoconfigured {@code ConcurrentKafkaListenerContainerFactory} wires in the
 * single {@link org.springframework.kafka.listener.CommonErrorHandler} bean found in the context
 * automatically — no factory bean of our own needed. See {@code OkgEventListener}'s sibling config
 * (same repo) for the identical rationale: without this, a routing failure was caught inside the
 * listener and logged, with the offset still committing — the message silently and permanently
 * lost. Now it retries with backoff and, on exhaustion, lands on {@code eip.agent-events.DLT}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "eip.orchestrator", name = "kafka-enabled", havingValue = "true")
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(120_000L);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
