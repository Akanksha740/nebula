package com.nebula.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Market entity - stores BTC/ETH/SOL Up/Down market data
 */
@Entity
@Table(name = "markets", indexes = {
    @Index(name = "idx_markets_slug", columnList = "slug", unique = true),
    @Index(name = "idx_markets_active", columnList = "active"),
    @Index(name = "idx_markets_coin", columnList = "coin"),
    @Index(name = "idx_markets_resolved", columnList = "resolved")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Coin coin;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "market_id")
    private String marketId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "market_type", length = 20)
    private String marketType;

    @Column(name = "condition_id")
    private String conditionId;

    @Column(name = "clob_token_up")
    private String clobTokenUp;

    @Column(name = "clob_token_down")
    private String clobTokenDown;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "coin_price_start", precision = 20, scale = 8)
    private BigDecimal coinPriceStart;

    @Column(name = "coin_price_end", precision = 20, scale = 8)
    private BigDecimal coinPriceEnd;

    @Column(length = 10)
    private String winner;

    @Column(name = "final_volume", precision = 20, scale = 2)
    private BigDecimal finalVolume;

    @Column(name = "final_liquidity", precision = 20, scale = 2)
    private BigDecimal finalLiquidity;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (coin == null && slug != null) {
            coin = Coin.fromSlug(slug);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
