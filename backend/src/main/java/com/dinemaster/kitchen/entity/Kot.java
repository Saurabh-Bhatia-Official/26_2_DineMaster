package com.dinemaster.kitchen.entity;



import com.dinemaster.orders.entity.Order;
import com.dinemaster.user.entity.User;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kot")
public class Kot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    @Column(name = "branch_id", nullable = false)
    private Long branchId;
    @Column(name = "kot_number", nullable = false, length = 50)
    private String kotNumber;
    @Column(nullable = false, length = 30)
    private String status = "Pending";
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "started_cooking_at")
    private OffsetDateTime startedCookingAt;
    @Column(name = "ready_at")
    private OffsetDateTime readyAt;
    @Column(name = "served_at")
    private OffsetDateTime servedAt;
    @Column(name = "delay_reason", columnDefinition = "TEXT")
    private String delayReason;
    @Column(name = "chef_name", length = 150)
    private String chefName;
    @Column(nullable = false, length = 30)
    private String priority = "Normal";
    @Column(name = "estimated_time", nullable = false)
    private Integer estimatedTime = 15;
    @Column(name = "station_key", nullable = false, length = 100)
    private String stationKey = "GENERAL";
    @Column(name = "round_no", nullable = false)
    private Integer roundNo = 1;
    @Column(name = "is_urgent", nullable = false)
    private boolean urgent;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_chef_id")
    private User assignedChef;
    @Column(name = "ticket_kind", nullable = false, length = 30)
    private String ticketKind = "ORDER";

    @PrePersist
    protected void onCreate() {
        if (createdAt == null)
            createdAt = OffsetDateTime.now();
        if (branchId == null && order != null && order.getRestaurant() != null)
            branchId = order.getRestaurant().getId();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order value) {
        order = value;
    }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long value) { branchId = value; }

    public String getKotNumber() {
        return kotNumber;
    }

    public void setKotNumber(String value) {
        kotNumber = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime value) {
        createdAt = value;
    }

    public OffsetDateTime getStartedCookingAt() {
        return startedCookingAt;
    }

    public void setStartedCookingAt(OffsetDateTime value) {
        startedCookingAt = value;
    }

    public OffsetDateTime getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(OffsetDateTime value) {
        readyAt = value;
    }

    public OffsetDateTime getServedAt() {
        return servedAt;
    }

    public void setServedAt(OffsetDateTime value) {
        servedAt = value;
    }

    public String getDelayReason() {
        return delayReason;
    }

    public void setDelayReason(String value) {
        delayReason = value;
    }

    public String getChefName() {
        return chefName;
    }

    public void setChefName(String value) {
        chefName = value;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String value) {
        priority = value;
    }

    public Integer getEstimatedTime() {
        return estimatedTime;
    }

    public void setEstimatedTime(Integer value) {
        estimatedTime = value;
    }
    public String getStationKey() { return stationKey; }
    public void setStationKey(String value) { stationKey = value; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer value) { roundNo = value; }
    public boolean isUrgent() { return urgent; }
    public void setUrgent(boolean value) { urgent = value; }
    public User getAssignedChef() { return assignedChef; }
    public void setAssignedChef(User value) { assignedChef = value; }
    public String getTicketKind() { return ticketKind; }
    public void setTicketKind(String value) { ticketKind = value; }
}
