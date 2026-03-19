package com.nebula.common.exception;

import lombok.Getter;

@Getter
public class TierAccessException extends NebulaException {
    private final String requiredTier;
    private final String currentTier;

    public TierAccessException(String message, String requiredTier, String currentTier) {
        super(message, "TIER_ACCESS_DENIED", 403);
        this.requiredTier = requiredTier;
        this.currentTier = currentTier;
    }

    public TierAccessException(String feature, String requiredTier) {
        super(
            String.format("Upgrade to %s tier to access %s", requiredTier, feature),
            "TIER_ACCESS_DENIED",
            403
        );
        this.requiredTier = requiredTier;
        this.currentTier = null;
    }
}
