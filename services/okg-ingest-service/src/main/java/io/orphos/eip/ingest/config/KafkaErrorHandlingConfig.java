package io.orphos.eip.ingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka-native retry/dead-lettering for {@code OkgEventListener}'s {@code okg-events} consumer.
 *
 * <p>Spring Boot's autoconfigured {@code ConcurrentKafkaListenerContainerFactory} wires in the
 * single {@link org.springframework.kafka.listener.CommonErrorHandler} bean found in the context
 * automatically — no factory bean of our own needed.</p>
 *
 * <p>Before this, a processing failure was caught inside the listener method and just logged: the
 * offset still committed, so the message was silently and permanently lost. Now a failure
 * propagates out of the listener (see {@code OkgEventListener}'s class javadoc), the container
 * retries it with exponential backoff, and — once the backoff budget is spent — publishes it to
 * {@code okg-events.DLT} (the default topic {@link DeadLetterPublishingRecoverer} picks) instead of
 * discarding it, so a stuck message is at least visible and replayable by an operator.</p>
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 1s, 2s, 4s, 8s, 16s, capped at 30s between attempts; give up after ~2 minutes total.
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(120_000L);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
