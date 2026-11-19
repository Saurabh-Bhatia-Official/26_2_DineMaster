package com.dinemaster.kitchen.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "print_jobs")
public class PrintJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false)
    private Long branchId;
    @Column(nullable = false, length = 50)
    private String kind = "KOT";
    @Column(name = "ref_id", nullable = false)
    private Long refId;
    @Column(name = "printer_id")
    private Long printerId;
    @Column(nullable = false, length = 30)
    private String status = "QUEUED";
    @Column(nullable = false)
    private Integer attempts = 0;
    @Column(columnDefinition = "TEXT")
    private String error;
    @Column(name = "requested_by", length = 150)
    private String requestedBy;
    @Column(name = "reprint_reason", columnDefinition = "TEXT")
    private String reprintReason;
    @Column(name = "duplicate_stamp", nullable = false)
    private boolean duplicateStamp;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
    public Long getId() { return id; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long value) { branchId = value; }
    public String getKind() { return kind; }
    public Long getRefId() { return refId; }
    public void setRefId(Long value) { refId = value; }
    public Long getPrinterId() { return printerId; }
    public void setPrinterId(Long value) { printerId = value; }
    public String getStatus() { return status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public String getError() { return error; }
    public void setError(String value) { error = value; }
    public void setStatus(String value) { status = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { requestedBy = value; }
    public String getReprintReason() { return reprintReason; }
    public void setReprintReason(String value) { reprintReason = value; }
    public boolean isDuplicateStamp() { return duplicateStamp; }
    public void setDuplicateStamp(boolean value) { duplicateStamp = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
