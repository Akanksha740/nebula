package com.nebula.common.dto;

import lombok.*;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageStatsDto {
    private long totalRequests;
    private long requestsToday;
    private long requestsThisMonth;
    private long dailyLimit;
    private long monthlyLimit;
    private long remainingToday;
    private double usagePercentage;
    private long bytesTransferredToday;
    private long bytesTransferredThisMonth;
    private String tier;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant resetsAt;
}
