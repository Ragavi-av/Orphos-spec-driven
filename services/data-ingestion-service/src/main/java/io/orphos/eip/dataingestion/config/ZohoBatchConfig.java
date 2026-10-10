package io.orphos.eip.dataingestion.config;

import io.orphos.eip.dataingestion.component.ZohoItemProcessor;
import io.orphos.eip.dataingestion.component.ZohoItemWriter;
import io.orphos.eip.dataingestion.dao.ZohoItemReader;
import io.orphos.eip.dataingestion.vo.ZohoMappedRecord;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Defines the Zoho ingestion {@link Job}/{@link Step} — chunk-oriented: read one Zoho row
 * ({@link ZohoItemReader}), map it ({@link ZohoItemProcessor}), and write chunks of mapped rows
 * ({@link ZohoItemWriter}).
 *
 * <p>{@code JobRepository}/{@code PlatformTransactionManager} here are Spring Batch's own types —
 * Spring Boot autoconfigures both against the embedded H2 database (see
 * {@code spring.batch.jdbc.initialize-schema=embedded} in application.yml and the {@code h2}
 * dependency in this module's pom), not this package's ArcadeDB-backed
 * {@code io.orphos.eip.dataingestion.repository.JobRepository} — same simple name, different
 * class; do not conflate the two when reading this file alongside
 * {@code io.orphos.eip.dataingestion.controller.ZohoBatchController}.</p>
 */
@Configuration
public class ZohoBatchConfig {

    @Value("${ingestion.zoho-batch.chunk-size:100}")
    private int chunkSize;

    @Bean
    public Job zohoIngestJob(JobRepository jobRepository, Step zohoIngestStep) {
        return new JobBuilder("zohoIngestJob", jobRepository)
                .start(zohoIngestStep)
                .build();
    }

    @Bean
    public Step zohoIngestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                               ZohoItemReader reader, ZohoItemProcessor processor, ZohoItemWriter writer) {
        return new StepBuilder("zohoIngestStep", jobRepository)
                .<Map<String, String>, ZohoMappedRecord>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
}
