package com.dinemaster.restaurant.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record BranchHoursResponse(List<BranchHour> hours) {
    public record BranchHour(Long id, Long branchId, DayOfWeek weekday, LocalTime openTime,
            LocalTime closeTime, boolean closed) { }
}
