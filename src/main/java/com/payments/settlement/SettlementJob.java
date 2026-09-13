package com.payments.settlement;

import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import com.payments.payment.PaymentStatusHistory;
import com.payments.payment.PaymentStatusHistoryRepository;
import com.payments.processor.ProcessorAdapter;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Explicit config-bean name so it doesn't collide with the @Bean Job named "settlementJob".
@Configuration("settlementJobConfig")
public class SettlementJob {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobLauncher jobLauncher;
    private final PaymentRepository paymentRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementEntryRepository settlementEntryRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final ProcessorAdapter processorAdapter;

    public SettlementJob(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         JobLauncher jobLauncher,
                         PaymentRepository paymentRepository,
                         SettlementBatchRepository settlementBatchRepository,
                         SettlementEntryRepository settlementEntryRepository,
                         PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                         ProcessorAdapter processorAdapter) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jobLauncher = jobLauncher;
        this.paymentRepository = paymentRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.settlementEntryRepository = settlementEntryRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.processorAdapter = processorAdapter;
    }

    @Bean
    public Job settlementJob() {
        return new JobBuilder("settlementJob", jobRepository)
                .start(settlementStep())
                .build();
    }

    @Bean
    public Step settlementStep() {
        return new StepBuilder("settlementStep", jobRepository)
                .tasklet(settlementTasklet(), transactionManager)
                .build();
    }

    private Tasklet settlementTasklet() {
        return (contribution, chunkContext) -> {
            Object dateParam = chunkContext.getStepContext().getJobParameters().get("date");
            LocalDate date = dateParam != null
                    ? LocalDate.parse(dateParam.toString())
                    : LocalDate.now(ZoneOffset.UTC);
            runSettlement(date);
            return RepeatStatus.FINISHED;
        };
    }

    /** Runs inside the step's transaction (the tasklet is wrapped by the step tx manager). */
    public void runSettlement(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        SettlementBatch batch = settlementBatchRepository.findBySettlementDate(date)
                .orElseGet(() -> settlementBatchRepository.save(SettlementBatch.create(date)));
        batch.markCalculating();
        settlementBatchRepository.save(batch);
        UUID batchId = batch.getId();

        // Step 1 — CollectPayments: CAPTURED payments for the date → settlement_entries,
        // transition each to SETTLEMENT_QUEUED.
        List<Payment> captured = paymentRepository.findByStatusAndCreatedAtBetween(
                PaymentStatus.CAPTURED, start, end);
        for (Payment p : captured) {
            if (settlementEntryRepository.findByPaymentId(p.getId()).isEmpty()) {
                settlementEntryRepository.save(SettlementEntry.create(
                        batchId, p.getId(), p.getSourceAccountId(), p.getDestAccountId(), p.getAmount()));
            }
            p.assignToSettlementBatch(batchId);   // CAPTURED -> SETTLEMENT_QUEUED
            paymentRepository.save(p);
            paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                    p.getId(), PaymentStatus.CAPTURED, PaymentStatus.SETTLEMENT_QUEUED,
                    "Queued for settlement"));
        }

        List<SettlementEntry> entries = settlementEntryRepository.findByBatchId(batchId);
        BigDecimal gross = entries.stream()
                .map(SettlementEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Step 2 — NetAndSubmit: netting done in the database, then submit each net instruction.
        BigDecimal net = BigDecimal.ZERO;
        for (Object[] row : settlementEntryRepository.computeNetting(batchId)) {
            BigDecimal netAmount = (BigDecimal) row[2];
            processorAdapter.charge(UUID.randomUUID().toString(), netAmount, "USD");
            net = net.add(netAmount);
        }

        // Settle all queued payments in the batch.
        for (Payment p : paymentRepository.findBySettlementBatchId(batchId)) {
            if (p.getStatus() == PaymentStatus.SETTLEMENT_QUEUED) {
                p.transitionTo(PaymentStatus.SETTLED);
                paymentRepository.save(p);
                paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                        p.getId(), PaymentStatus.SETTLEMENT_QUEUED, PaymentStatus.SETTLED, "Settled"));
            }
        }

        batch.recordTotals(entries.size(), gross, net);
        batch.markSettled(Instant.now());
        settlementBatchRepository.save(batch);
    }

    @Scheduled(cron = "0 0 23 * * *", zone = "UTC")
    @SchedulerLock(name = "settlementJob", lockAtMostFor = "2h", lockAtLeastFor = "1m")
    public void scheduledSettlement() throws Exception {
        jobLauncher.run(settlementJob(), new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());
    }
}
