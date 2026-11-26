package com.dinemaster.kitchen.service;

import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.kitchen.entity.PrintJob;
import com.dinemaster.kitchen.repository.PrintJobRepository;
import com.dinemaster.orders.service.OrderAuditService;
import com.dinemaster.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@Service
public class PrintJobService {
    private final PrintJobRepository jobs;
    private final OrderAuditService audit;
    private final boolean printingEnabled;

    public PrintJobService(PrintJobRepository jobs, OrderAuditService audit,
            @Value("${dinemaster.printing.enabled:false}") boolean printingEnabled) {
        this.jobs = jobs; this.audit = audit; this.printingEnabled = printingEnabled;
    }

    @Transactional
    public PrintJob enqueueKot(Kot kot, User actor, String reason, Long printerId, boolean duplicate) {
        if (!duplicate && !printingEnabled) return null;
        PrintJob job = new PrintJob(); job.setBranchId(kot.getBranchId()); job.setRefId(kot.getId());
        job.setPrinterId(printerId); job.setRequestedBy(actor == null ? "SYSTEM" : actor.getUsername());
        job.setReprintReason(reason); job.setDuplicateStamp(duplicate);
        PrintJob saved = jobs.save(job);
        audit.event(kot.getOrder().getId(), duplicate ? "kot.reprint_requested" : "kot.print_queued",
                Map.of("kotId", kot.getId(), "printJobId", saved.getId(), "duplicate", duplicate));
        return saved;
    }

    @Transactional
    public PrintJob retry(Long id, Long branchId) {
        PrintJob job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found"));
        if (!branchId.equals(job.getBranchId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found");
        if (!"FAILED".equalsIgnoreCase(job.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed print jobs can be retried");
        job.setStatus("QUEUED"); job.setError(null);
        return jobs.save(job);
    }

    @Transactional(readOnly = true)
    public List<PrintJob> failed(Long branchId) {
        return jobs.findByBranchIdAndStatusOrderByCreatedAtAsc(branchId, "FAILED");
    }
}
