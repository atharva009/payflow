package com.payments.config;

import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MicrometerConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags(
            PaymentRepository paymentRepository) {
        return registry -> {
            registry.config().commonTags("service", "payflow");
            Gauge.builder("payments.active", paymentRepository,
                            repo -> repo.countByStatus(PaymentStatus.PENDING)
                                    + repo.countByStatus(PaymentStatus.AUTHORIZED)
                                    + repo.countByStatus(PaymentStatus.CAPTURED))
                    .description("Number of non-terminal payments")
                    .register(registry);
        };
    }
}
