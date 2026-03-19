package com.nebula.api.repository;

import com.nebula.common.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("SELECT k FROM ApiKey k JOIN FETCH k.customer WHERE k.keyHash = :keyHash")
    Optional<ApiKey> findByKeyHash(@Param("keyHash") String keyHash);

    List<ApiKey> findByCustomerIdAndIsActiveTrue(UUID customerId);

    List<ApiKey> findByCustomerId(UUID customerId);

    @Modifying
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :lastUsedAt WHERE k.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("lastUsedAt") Instant lastUsedAt);

    @Query("SELECT COUNT(k) FROM ApiKey k WHERE k.customer.id = :customerId AND k.isActive = true")
    long countActiveKeysByCustomerId(@Param("customerId") UUID customerId);

    @Modifying
    @Query("UPDATE ApiKey k SET k.isActive = false WHERE k.id = :id AND k.customer.id = :customerId")
    int revokeKey(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
