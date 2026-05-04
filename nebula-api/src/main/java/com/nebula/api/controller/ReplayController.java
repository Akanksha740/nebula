package com.nebula.api.controller;

import com.nebula.api.service.ReplayEngineService;
import com.nebula.common.dto.ReplayResultDto;
import com.nebula.common.dto.request.ReplayRequest;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.NebulaException;
import com.nebula.common.exception.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/replay")
@RequiredArgsConstructor
@Tag(name = "Strategy Replay", description = "Replay trading strategies against historical markets")
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final ReplayEngineService replayEngineService;

    /**
     * Replay a strategy against a historical resolved market.
     * Evaluates entry/exit conditions tick-by-tick against real order book data,
     * simulates fills with actual slippage, and returns full PnL analysis.
     *
     * Requires PRO or ENTERPRISE tier.
     */
    @PostMapping
    @Operation(summary = "Replay a strategy against a historical market")
    public ResponseEntity<ApiResponse<ReplayResultDto>> replayStrategy(
            @AuthenticationPrincipal Customer customer,
            @Valid @RequestBody ReplayRequest request) {

        if (customer == null) {
            throw new UnauthorizedException("Authentication required");
        }

        // PRO and ENTERPRISE only
        if (customer.getTier() != Customer.SubscriptionTier.PRO
                && customer.getTier() != Customer.SubscriptionTier.PRO_TRIAL
                && customer.getTier() != Customer.SubscriptionTier.ENTERPRISE) {
            throw new NebulaException(
                    "Strategy Replay requires a Pro or Enterprise subscription. Upgrade to access this feature.",
                    "TIER_REQUIRED", 403);
        }

        log.info("Strategy replay: customer={}, market={}, side={}",
                customer.getEmail(), request.getMarketSlug(), request.getSide());

        ReplayResultDto result = replayEngineService.replay(customer, request);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
