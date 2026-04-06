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
import com.nebula.common.entity.Customer.SubscriptionTier;
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
    private final ApiAccessService apiAccessService;

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
        String warning = null;
        int maxMarkets = Integer.MAX_VALUE;
        if (customer != null && marketType != null) {
            TierConfig.TierLimits tierLimits = TierConfig.getLimits(customer.getTier());
            maxMarkets = tierLimits.getMarketLimit(marketType);
            if (maxMarkets != Integer.MAX_VALUE) {
                if (offset >= maxMarkets) {
                    warning = String.format("%s plan limit reached. Upgrade to PRO to view all markets.",
                            customer.getTier());
                    return MarketsListResponse.builder()
                            .markets(List.of())
                            .total(0)
                            .limit(limit)
                            .offset(offset)
                            .warning(warning)
                            .build();
                }
                int remaining = maxMarkets - offset;
                if (limit > remaining) {
                    limit = remaining;
                }
            }
        }

        limit = Math.max(limit, 1);

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

        if (maxMarkets != Integer.MAX_VALUE && offset + limit >= maxMarkets) {
            warning = String.format("%s plan limit reached. Showing latest %d of %d allowed markets. Upgrade to PRO to view all markets.",
                    customer.getTier(), marketDtos.size(), maxMarkets);
        }

        return MarketsListResponse.builder()
                .markets(marketDtos)
                .total((int) total)
                .limit(limit)
                .offset(offset)
                .warning(warning)
                .build();
    }

    public MarketDto getMarketBySlug(String slug) {
        Market market = marketRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Market", slug));
        return toDto(market);
    }

    public MarketWithSnapshotsResponse getMarketWithSnapshotsByMarketId(Customer customer, String marketId, int limit, int offset, boolean includeOrderbook) {
        Market market = marketRepository.findByMarketId(marketId)
                .orElseThrow(() -> new ResourceNotFoundException("Market", marketId));
        apiAccessService.checkCoinAccess(customer, market.getCoin());
        return buildMarketWithSnapshotsResponse(customer, market, limit, offset, includeOrderbook);
    }

    public MarketWithSnapshotsResponse getMarketWithSnapshots(Customer customer, String slug, int limit, int offset, boolean includeOrderbook) {
        Market market = marketRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Market", slug));
        return buildMarketWithSnapshotsResponse(customer, market, limit, offset, includeOrderbook);
    }

    private MarketWithSnapshotsResponse buildMarketWithSnapshotsResponse(Customer customer, Market market, int limit, int offset, boolean includeOrderbook) {
        // STARTER users can only access snapshots for the most recent N markets per type
        if (customer != null && customer.getTier() == SubscriptionTier.STARTER
                && market.getMarketType() != null && market.getStartTime() != null) {
            int maxMarkets = TierConfig.getLimits(customer.getTier()).getMarketLimit(market.getMarketType());
            if (maxMarkets != Integer.MAX_VALUE) {
                long newerMarketsCount = marketRepository.countByCoinAndMarketTypeAndStartTimeAfter(
                        market.getCoin(), market.getMarketType(), market.getStartTime());
                if (newerMarketsCount >= maxMarkets) {
                    throw new TierAccessException(
                            String.format("This market is beyond your STARTER plan limit of %d most recent %s markets. Upgrade to PRO to access all market snapshots.",
                                    maxMarkets, market.getMarketType()),
                            "PRO", "STARTER");
                }
            }
        }

        long total = snapshotRepository.countByMarketId(market.getId());

        List<MarketSnapshot> snapshots = snapshotRepository.findByMarketIdOrderByTimeDesc(
                market.getId(), PageRequest.of(offset / limit, limit));

        List<MarketSnapshotDto> snapshotDtos = snapshots.stream()
                .map(s -> toSnapshotDto(s, includeOrderbook))
                .toList();

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
                .isResolved(market.getIsResolved())
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
