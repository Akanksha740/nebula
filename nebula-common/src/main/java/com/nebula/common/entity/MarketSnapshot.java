package com.nebula.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * MarketSnapshot entity - stores price snapshots with orderbook data
 */
@Entity
@Table(name = "market_snapshots", indexes = {
    @Index(name = "idx_snapshots_market_time", columnList = "market_id, time DESC"),
    @Index(name = "idx_snapshots_time", columnList = "time DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_id", nullable = false)
    private Market market;

    @Column(nullable = false)
    private Instant time;

    @Column(name = "btc_price", precision = 20, scale = 8)
    private BigDecimal btcPrice;

    @Column(name = "price_up", precision = 10, scale = 6)
    private BigDecimal priceUp;

    @Column(name = "price_down", precision = 10, scale = 6)
    private BigDecimal priceDown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "orderbook_up", columnDefinition = "jsonb")
    private Map<String, Object> orderbookUp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "orderbook_down", columnDefinition = "jsonb")
    private Map<String, Object> orderbookDown;

    @Column(precision = 20, scale = 2)
    private BigDecimal volume;

    @Column(precision = 20, scale = 2)
    private BigDecimal liquidity;

    @PrePersist
    protected void onCreate() {
        if (time == null) {
            time = Instant.now();
        }
    }
}
