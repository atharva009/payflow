package com.payments.admin;

import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import com.payments.reconciliation.ReconciliationReport;
import com.payments.reconciliation.ReconciliationReportRepository;
import com.payments.settlement.SettlementBatch;
import com.payments.settlement.SettlementBatchRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final JobLauncher jobLauncher;
    private final Job settlementJob;
    private final Job reconciliationJob;
    private final PaymentRepository paymentRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final SettlementBatchRepository settlementBatchRepository;

    public AdminController(JobLauncher jobLauncher,
                           Job settlementJob,
                           Job reconciliationJob,
                           PaymentRepository paymentRepository,
                           ReconciliationReportRepository reconciliationReportRepository,
                           SettlementBatchRepository settlementBatchRepository) {
        this.jobLauncher = jobLauncher;
        this.settlementJob = settlementJob;
        this.reconciliationJob = reconciliationJob;
        this.paymentRepository = paymentRepository;
        this.reconciliationReportRepository = reconciliationReportRepository;
        this.settlementBatchRepository = settlementBatchRepository;
    }

    @GetMapping("/payments")
    public ResponseEntity<Page<Payment>> listPayments(
            @RequestParam PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentRepository.findByStatus(status, PageRequest.of(page, size)));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<List<ReconciliationReport>> listReconciliationReports() {
        return ResponseEntity.ok(reconciliationReportRepository.findAll());
    }

    @GetMapping("/reconciliation/{id}")
    public ResponseEntity<ReconciliationReport> reconciliationReport(@PathVariable UUID id) {
        return reconciliationReportRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<Void> runReconciliation() throws Exception {
        jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/settlement")
    public ResponseEntity<List<SettlementBatch>> listSettlementBatches() {
        return ResponseEntity.ok(settlementBatchRepository.findAll());
    }

    @PostMapping("/settlement/{batchId}/retry")
    public ResponseEntity<Void> retrySettlement(@PathVariable UUID batchId) throws Exception {
        SettlementBatch batch = settlementBatchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            return ResponseEntity.notFound().build();
        }
        batch.resetForRetry();
        settlementBatchRepository.save(batch);
        jobLauncher.run(settlementJob, new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .addString("date", batch.getSettlementDate().toString())
                .toJobParameters());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
