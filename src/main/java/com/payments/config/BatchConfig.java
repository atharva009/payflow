package com.payments.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing
public class BatchConfig {
    // JobRepository and PlatformTransactionManager are auto-configured by Spring Boot 4
    // + the batch starter. No manual DataSource wiring needed.
}
