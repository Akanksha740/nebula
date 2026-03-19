package com.nebula.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_usage", indexes = {
    @Index(name = "idx_usage_customer_time", columnList = "customerId, requestedAt DESC"),
    @Index(name = "idx_usage_api_key_time", columnList = "apiKeyId, requestedAt DESC"),
    @Index(name = "idx_usage_requested_at", columnList = "requestedAt DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(nullable = false)
    private String endpoint;

    @Column(length = 10)
    private String method;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Column(name = "request_bytes")
    private Long requestBytes;

    @Column(name = "response_bytes")
    private Long responseBytes;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
    }
}
