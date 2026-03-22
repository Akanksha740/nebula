package com.nebula.api.repository;

import com.nebula.common.entity.ApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ApiUsageRepository extends JpaRepository<ApiUsage, UUID> {

    @Query("SELECT COUNT(u) FROM ApiUsage u WHERE u.customerId = :customerId " +
           "AND u.requestedAt >= :since")
    long countRequestsSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Query("SELECT COUNT(u) FROM ApiUsage u WHERE u.apiKeyId = :apiKeyId " +
           "AND u.requestedAt >= :since")
    long countRequestsByApiKeySince(
            @Param("apiKeyId") UUID apiKeyId,
            @Param("since") Instant since);

    @Query("SELECT COUNT(u) FROM ApiUsage u WHERE u.customerId = :customerId " +
           "AND u.requestedAt >= :since AND u.statusCode = 200")
    long countSuccessfulRequestsSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Query("SELECT COUNT(u) FROM ApiUsage u WHERE u.customerId = :customerId " +
           "AND u.requestedAt >= :since AND u.statusCode = 429")
    long countRateLimitedRequestsSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(u.responseBytes), 0) FROM ApiUsage u " +
           "WHERE u.customerId = :customerId AND u.requestedAt >= :since")
    long sumResponseBytesSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Query("SELECT COUNT(DISTINCT u.endpoint) FROM ApiUsage u " +
           "WHERE u.customerId = :customerId AND u.requestedAt >= :since")
    long countDistinctEndpointsSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Query("SELECT COALESCE(AVG(u.responseTimeMs), 0) FROM ApiUsage u " +
           "WHERE u.customerId = :customerId AND u.requestedAt >= :since")
    double averageResponseTimeSince(
            @Param("customerId") UUID customerId,
            @Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM ApiUsage u WHERE u.requestedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
