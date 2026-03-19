package com.nebula.common.entity;

public enum Coin {
    BTC,
    ETH,
    SOL;

    public static Coin fromSlug(String slug) {
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        
        String lowerSlug = slug.toLowerCase();
        
        // Extract first word (before first hyphen)
        String firstWord = lowerSlug.split("-")[0];
        
        // BTC: "btc" or "bitcoin"
        if ("btc".equals(firstWord) || "bitcoin".equals(firstWord)) {
            return BTC;
        }
        
        // ETH: "eth" or "ethereum"
        if ("eth".equals(firstWord) || "ethereum".equals(firstWord)) {
            return ETH;
        }
        
        // SOL: "sol" or "solana"
        if ("sol".equals(firstWord) || "solana".equals(firstWord)) {
            return SOL;
        }
        
        return null;
    }
}
