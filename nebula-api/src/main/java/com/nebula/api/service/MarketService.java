package com.nebula.api.service;

import com.nebula.api.repository.MarketRepository;
import com.nebula.api.repository.MarketSnapshotRepository;
import com.nebula.common.dto.MarketDto;
import com.nebula.common.dto.MarketSnapshotDto;
import com.nebula.common.dto.MarketsListResponse;
import com.nebula.common.dto.MarketWithSnapshotsResponse;
import com.nebula.common.config.TierConfig;
import com.nebula.common.entity.Coin;
import com.nebula.common.entity.Customer;
import com.nebula.common.entity.Market;
import com.nebula.common.entity.MarketSnapshot;
import com.nebula.common.exception.ResourceNotFoundException;
import com.nebula.common.exception.TierAccessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private final MarketRepository marketRepository;
    private final MarketSnapshotRepository snapshotRepository;

    public MarketsListResponse getMarkets(
            Customer customer,
            Coin coin,
            String marketType,
            Boolean resolved,
            Instant startTimeAfter,
            Instant startTimeBefore,
            int limit,
            int offset) {

        // Enforce tier-based market history limits
        if (customer != null) {
            TierConfig.TierLimits tierLimits = TierConfig.getLimits(customer.getTier());
            int maxMarkets = tierLimits.getMarketLimit(marketType);
            if (limit > maxMarkets) limit = maxMarkets;
            if (offset >= maxMarkets) {
                return MarketsListResponse.builder()
                        .markets(List.of())
                        .total(0)
                        .limit(limit)
                        .offset(offset)
                        .build();
            }
            if (offset + limit > maxMarkets) {
                limit = maxMarkets - offset;
            }
        }

        Pageable pageable = PageRequest.of(offset / limit, limit);
        Page<Market> page;
        long total;

        // Query based on filters
        if (marketType != null && resolved != null) {
            if (resolved) {
                page = marketRepository.findByCoinAndMarketTypeAndResolvedTrueOrderByStartTimeDesc(coin, marketType, pageable);
                total = marketRepository.countByCoinAndMarketTypeAndResolvedTrue(coin, marketType);
            } else {
                page = marketRepository.findByCoinAndMarketTypeAndResolvedFalseOrderByStartTimeDesc(coin, marketType, pageable);
                total = marketRepository.countByCoinAndMarketTypeAndResolvedFalse(coin, marketType);
            }
        } else if (marketType != null) {
            page = marketRepository.findByCoinAndMarketTypeOrderByStartTimeDesc(coin, marketType, pageable);
            total = marketRepository.countByCoinAndMarketType(coin, marketType);
        } else if (resolved != null) {
            if (resolved) {
                page = marketRepository.findByCoinAndResolvedTrueOrderByStartTimeDesc(coin, pageable);
                total = marketRepository.countByCoinAndResolvedTrue(coin);
            } else {
                page = marketRepository.findByCoinAndResolvedFalseOrderByStartTimeDesc(coin, pageable);
                total = marketRepository.countByCoinAndResolvedFalse(coin);
            }
        } else {
            page = marketRepository.findByCoinOrderByStartTimeDesc(coin, pageable);
            total = marketRepository.countByCoin(coin);
        }

        // Apply time filters in-memory if needed
        List<MarketDto> marketDtos = page.getContent().stream()
                .filter(m -> startTimeAfter == null || (m.getStartTime() != null && !m.getStartTime().isBefore(startTimeAfter)))
                .filter(m -> startTimeBefore == null || (m.getStartTime() != null && !m.getStartTime().isAfter(startTimeBefore)))
                .map(this::toDto)
                .collect(Collectors.toList());

        // Cap total to tier limit so frontend shows correct pagination
        if (customer != null) {
            int maxMarkets = TierConfig.getLimits(customer.getTier()).getMarketLimit(marketType);
            if (total > maxMarkets) total = maxMarkets;
        }

        return MarketsListResponse.builder()
                .markets(marketDtos)
                .total((int) total)
                .limit(limit)
                .offset(offset)
                .build();
    }

    public List<MarketDto> getAllMarkets() {
        return marketRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    public List<MarketDto> getActiveMarkets() {
        return marketRepository.findByActiveTrue().stream()
                .map(this::toDto)
                .toList();
    }

    public MarketDto getMarketBySlug(String slug) {
        Market market = marketRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Market", slug));
        return toDto(market);
    }

    public MarketWithSnapshotsResponse getMarketWithSnapshots(Customer customer, String slug, int limit, int offset, boolean includeOrderbook) {
        Market market = marketRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Market", slug));

        // Cap limit to 1000 for all users
        if (limit > 1000) limit = 1000;

        // Enforce tier-based snapshot limits
        if (customer != null) {
            TierConfig.TierLimits tierLimits = TierConfig.getLimits(customer.getTier());
            int maxSnapshots = tierLimits.getSnapshotLimit(market.getMarketType());
            if (offset >= maxSnapshots || offset + limit > maxSnapshots) {
                throw new TierAccessException(
                        String.format("Your %s plan allows access to the last %d snapshots for %s markets. Upgrade to PRO for unlimited snapshot access.",
                                customer.getTier().name(), maxSnapshots, market.getMarketType()),
                        "PRO",
                        customer.getTier().name());
            }
            if (limit > maxSnapshots) limit = maxSnapshots;
        }

        long total = snapshotRepository.countByMarketId(market.getId());

        List<MarketSnapshot> snapshots = snapshotRepository.findByMarketIdOrderByTimeDesc(
                market.getId(), PageRequest.of(offset / limit, limit));

        List<MarketSnapshotDto> snapshotDtos = snapshots.stream()
                .map(s -> toSnapshotDto(s, includeOrderbook))
                .toList();

        // Cap total to tier limit
        if (customer != null) {
            int maxSnapshots = TierConfig.getLimits(customer.getTier()).getSnapshotLimit(market.getMarketType());
            if (total > maxSnapshots) total = maxSnapshots;
        }

        return MarketWithSnapshotsResponse.builder()
                .market(toDto(market))
                .snapshots(snapshotDtos)
                .total((int) total)
                .limit(limit)
                .offset(offset)
                .build();
    }

    private MarketDto toDto(Market market) {
        return MarketDto.builder()
                .marketId(market.getMarketId())
                .eventId(market.getEventId())
                .slug(market.getSlug())
                .marketType(market.getMarketType())
                .startTime(market.getStartTime())
                .endTime(market.getEndTime())
                .coinPriceStart(market.getCoinPriceStart())
                .conditionId(market.getConditionId())
                .clobTokenUp(market.getClobTokenUp())
                .clobTokenDown(market.getClobTokenDown())
                .winner(market.getWinner())
                .finalVolume(market.getFinalVolume())
                .finalLiquidity(market.getFinalLiquidity())
                .coinPriceEnd(market.getCoinPriceEnd())
                .resolvedAt(market.getResolvedAt())
                .createdAt(market.getCreatedAt())
                .updatedAt(market.getUpdatedAt())
                .build();
    }

    private MarketSnapshotDto toSnapshotDto(MarketSnapshot snapshot, boolean includeOrderbook) {
        MarketSnapshotDto.MarketSnapshotDtoBuilder builder = MarketSnapshotDto.builder()
                .time(snapshot.getTime())
                .coinPrice(snapshot.getCoinPrice())
                .priceUp(snapshot.getPriceUp())
                .priceDown(snapshot.getPriceDown());

        if (includeOrderbook) {
            builder.orderbookUp(snapshot.getOrderbookUp());
            builder.orderbookDown(snapshot.getOrderbookDown());
        }

        return builder.build();
    }
}
