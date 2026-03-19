package com.nebula.api.service;

import com.nebula.api.repository.ApiUsageRepository;
import com.nebula.common.config.TierConfig;
import com.nebula.common.dto.UsageStatsDto;
import com.nebula.common.entity.ApiUsage;
import com.nebula.common.entity.Customer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UsageTrackingService {

    private static final Logger log = LoggerFactory.getLogger(UsageTrackingService.class);
    private final ApiUsageRepository usageRepository;

    @Async
    public void trackRequest(UUID customerId, UUID apiKeyId, String endpoint, String method,
                            int statusCode, int responseTimeMs, long requestBytes, 
                            long responseBytes, String ipAddress, String userAgent) {
        try {
            ApiUsage usage = ApiUsage.builder()
                    .customerId(customerId)
                    .apiKeyId(apiKeyId)
                    .endpoint(endpoint)
                    .method(method)
                    .statusCode(statusCode)
                    .responseTimeMs(responseTimeMs)
                    .requestBytes(requestBytes)
                    .responseBytes(responseBytes)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .requestedAt(Instant.now())
                    .build();

            usageRepository.save(usage);
        } catch (Exception e) {
            log.error("Failed to track API usage", e);
        }
    }

    public UsageStatsDto getUsageStats(Customer customer) {
        UUID customerId = customer.getId();
        TierConfig.TierLimits limits = TierConfig.getLimits(customer.getTier());

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        
        Instant startOfMonth = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.firstDayOfMonth())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        Instant endOfMonth = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.lastDayOfMonth())
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        Instant resetsAt = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        long requestsToday = usageRepository.countRequestsSince(customerId, startOfDay);
        long requestsThisMonth = usageRepository.countRequestsSince(customerId, startOfMonth);
        long bytesToday = usageRepository.sumResponseBytesSince(customerId, startOfDay);
        long bytesThisMonth = usageRepository.sumResponseBytesSince(customerId, startOfMonth);

        long remainingToday = Math.max(0, limits.dailyRequestLimit() - requestsToday);
        double usagePercentage = (double) requestsToday / limits.dailyRequestLimit() * 100;

        return UsageStatsDto.builder()
                .requestsToday(requestsToday)
                .requestsThisMonth(requestsThisMonth)
                .dailyLimit(limits.dailyRequestLimit())
                .monthlyLimit((long) limits.dailyRequestLimit() * 30)
                .remainingToday(remainingToday)
                .usagePercentage(Math.min(100, usagePercentage))
                .bytesTransferredToday(bytesToday)
                .bytesTransferredThisMonth(bytesThisMonth)
                .tier(customer.getTier().name())
                .periodStart(startOfMonth)
                .periodEnd(endOfMonth)
                .resetsAt(resetsAt)
                .build();
    }
}
