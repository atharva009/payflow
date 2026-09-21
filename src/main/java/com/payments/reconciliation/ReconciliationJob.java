package com.payments.reconciliation;

import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import com.payments.processor.ProcessorAdapter;
import com.payments.processor.ProcessorRecord;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Explicit config-bean name so it doesn't collide with the @Bean Job named "reconciliationJob".
@Configuration("reconciliationJobConfig")
public class ReconciliationJob {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobLauncher jobLauncher;
    private final PaymentRepository paymentRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final ProcessorAdapter processorAdapter;
    private final ObjectMapper objectMapper;

    public ReconciliationJob(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             JobLauncher jobLauncher,
                             PaymentRepository paymentRepository,
                             ReconciliationReportRepository reconciliationReportRepository,
                             ProcessorAdapter processorAdapter,
                             ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jobLauncher = jobLauncher;
        this.paymentRepository = paymentRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.processorAdapter = processorAdapter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public Job reconciliationJob() {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep())
                .build();
    }

    @Bean
    public Step reconciliationStep() {
        return new StepBuilder("reconciliationStep", jobRepository)
                .tasklet(reconciliationTasklet(), transactionManager)
                .build();
    }

    private Tasklet reconciliationTasklet() {
        return (contribution, chunkContext) -> {
            Object dateParam = chunkContext.getStepContext().getJobParameters().get("date");
            // Scheduled run reconciles yesterday; a manual/test run may pass an explicit date.
            LocalDate date = dateParam != null
                    ? LocalDate.parse(dateParam.toString())
                    : LocalDate.now(ZoneOffset.UTC).minusDays(1);
            runReconciliation(date);
            return RepeatStatus.FINISHED;
        };
    }

    public void runReconciliation(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        ReconciliationReport report = reconciliationReportRepository.findByReportDate(date)
                .orElseGet(() -> reconciliationReportRepository.save(ReconciliationReport.create(date)));
        report.markRunning();
        reconciliationReportRepository.save(report);

        // Step 1 — FetchProcessorRecords
        List<ProcessorRecord> processorRecords = processorAdapter.getProcessedPayments(date);
        Map<String, ProcessorRecord> byRef = processorRecords.stream()
                .collect(Collectors.toMap(ProcessorRecord::processorRef, r -> r, (a, b) -> a));

        // Internal view: payments that carry a processor_ref for the date.
        List<Payment> internal = paymentRepository
                .findByProcessorRefIsNotNullAndCreatedAtBetween(start, end);

        // Step 2 — CompareRecords
        List<Map<String, Object>> discrepancies = new ArrayList<>();
        Set<String> matchedRefs = new HashSet<>();
        int matched = 0;

        for (Payment p : internal) {
            ProcessorRecord rec = byRef.get(p.getProcessorRef());
            if (rec == null) {
                discrepancies.add(entry(p.getId().toString(), p.getProcessorRef(),
                        "MISSING_IN_PROCESSOR", p.getAmount().toPlainString(), null));
                continue;
            }
            matchedRefs.add(p.getProcessorRef());
            if (p.getAmount().compareTo(rec.amount()) != 0) {
                discrepancies.add(entry(p.getId().toString(), p.getProcessorRef(),
                        "AMOUNT_MISMATCH", p.getAmount().toPlainString(), rec.amount().toPlainString()));
            } else if (!p.getStatus().name().equals(rec.status())) {
                String type = (p.getStatus() == PaymentStatus.PENDING
                        || p.getStatus() == PaymentStatus.AUTHORIZED)
                        ? "TIMING_DISCREPANCY" : "STATUS_MISMATCH";
                discrepancies.add(entry(p.getId().toString(), p.getProcessorRef(),
                        type, p.getAmount().toPlainString(), rec.amount().toPlainString()));
            } else {
                matched++;
            }
        }

        for (ProcessorRecord rec : processorRecords) {
            if (!matchedRefs.contains(rec.processorRef())) {
                discrepancies.add(entry(null, rec.processorRef(),
                        "MISSING_IN_LEDGER", null, rec.amount().toPlainString()));
            }
        }

        // Step 3 — GenerateReport
        report.recordResults(internal.size(), matched, discrepancies.size(), serialize(discrepancies));
        report.markCompleted(Instant.now());
        reconciliationReportRepository.save(report);
    }

    private Map<String, Object> entry(String paymentId, String processorRef, String type,
                                      String internalAmount, String processorAmount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", paymentId);
        m.put("processorRef", processorRef);
        m.put("type", type);
        m.put("internalAmount", internalAmount);
        m.put("processorAmount", processorAmount);
        return m;
    }

    private String serialize(List<Map<String, Object>> discrepancies) {
        return objectMapper.writeValueAsString(discrepancies);
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    @SchedulerLock(name = "reconciliationJob", lockAtMostFor = "2h", lockAtLeastFor = "1m")
    public void scheduledReconciliation() throws Exception {
        jobLauncher.run(reconciliationJob(), new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());
    }
}
