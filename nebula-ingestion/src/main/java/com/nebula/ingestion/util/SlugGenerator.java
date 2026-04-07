package com.nebula.ingestion.util;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates Polymarket slug identifiers for BTC and ETH Up/Down markets
 * based on the current UTC time and market type intervals.
 *
 * BTC slug patterns:
 *   5m  → btc-updown-5m-{epoch}
 *   15m → btc-updown-15m-{epoch}
 *   1h  → bitcoin-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
 *         OR bitcoin-up-or-down-{month}-{day}-{hour}{am/pm}-et
 *   4h  → btc-updown-4h-{epoch}
 *   24h → bitcoin-up-or-down-on-{month}-{day}-{year}
 *
 * ETH slug patterns:
 *   5m  → eth-updown-5m-{epoch}
 *   15m → eth-updown-15m-{epoch}
 *   1h  → ethereum-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
 *         OR ethereum-up-or-down-{month}-{day}-{hour}{am/pm}-et
 *   4h  → eth-updown-4h-{epoch}
 *   24h → ethereum-up-or-down-on-{month}-{day}-{year}
 *
 * SOL slug patterns:
 *   5m  → sol-updown-5m-{epoch}
 *   15m → sol-updown-15m-{epoch}
 *   1h  → solana-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
 *         OR solana-up-or-down-{month}-{day}-{hour}{am/pm}-et
 *   4h  → sol-updown-4h-{epoch}
 *   24h → solana-up-or-down-on-{month}-{day}-{year}
 */
public final class SlugGenerator {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final int SECONDS_5M = 300;
    private static final int SECONDS_15M = 900;
    private static final int SECONDS_4H = 14_400;

    private SlugGenerator() {}

    /**
     * Generate slugs for all market types based on the current time.
     */
    public static List<String> generateCurrentSlugs() {
        return generateSlugs(Instant.now());
    }

    /**
     * Generate slugs for all market types (BTC + ETH) based on a given instant.
     */
    public static List<String> generateSlugs(Instant now) {
        List<String> slugs = new ArrayList<>(18);
        slugs.addAll(generateShortMarketSlugs(now));
        slugs.addAll(generateLongMarketSlugs(now));
        return slugs;
    }

    /**
     * Generate slugs for short-duration markets (5m, 15m, 1hr).
     */
    public static List<String> generateShortMarketSlugs(Instant now) {
        List<String> slugs = new ArrayList<>(12);
        slugs.add(generate5mSlug(now));
        slugs.add(generate15mSlug(now));
        slugs.add(generate1hSlug(now));
        slugs.add(generate1hSlugNoYear(now));
        slugs.add(generateEth5mSlug(now));
        slugs.add(generateEth15mSlug(now));
        slugs.add(generateEth1hSlug(now));
        slugs.add(generateEth1hSlugNoYear(now));
        slugs.add(generateSol5mSlug(now));
        slugs.add(generateSol15mSlug(now));
        slugs.add(generateSol1hSlug(now));
        slugs.add(generateSol1hSlugNoYear(now));
        return slugs;
    }

    /**
     * Generate slugs for long-duration markets (4h, 24h).
     */
    public static List<String> generateLongMarketSlugs(Instant now) {
        List<String> slugs = new ArrayList<>(6);
        slugs.add(generate4hSlug(now));
        slugs.add(generate24hSlug(now));
        slugs.add(generateEth4hSlug(now));
        slugs.add(generateEth24hSlug(now));
        slugs.add(generateSol4hSlug(now));
        slugs.add(generateSol24hSlug(now));
        return slugs;
    }

    /**
     * 5-minute market slug: btc-updown-5m-{epoch}
     * Epoch is floored to the nearest 5-minute boundary.
     */
    public static String generate5mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_5M) * SECONDS_5M;
        return "btc-updown-5m-" + slot;
    }

    /**
     * 15-minute market slug: btc-updown-15m-{epoch}
     * Epoch is floored to the nearest 15-minute boundary.
     */
    public static String generate15mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_15M) * SECONDS_15M;
        return "btc-updown-15m-" + slot;
    }

    /**
     * 1-hour market slug: bitcoin-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
     * Uses the start-of-hour in Eastern Time.
     */
    public static String generate1hSlug(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int year = startOfHour.getYear();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("bitcoin-up-or-down-%s-%d-%d-%d%s-et",
                month, day, year, displayHour, ampm);
    }

    /**
     * 1-hour market slug without year: bitcoin-up-or-down-{month}-{day}-{hour}{am/pm}-et
     */
    public static String generate1hSlugNoYear(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("bitcoin-up-or-down-%s-%d-%d%s-et",
                month, day, displayHour, ampm);
    }

    /**
     * 4-hour market slug: btc-updown-4h-{epoch}
     * Epoch is floored to the nearest 4-hour boundary (aligned to UTC midnight).
     */
    public static String generate4hSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_4H) * SECONDS_4H;
        return "btc-updown-4h-" + slot;
    }

    /**
     * 24-hour market slug: bitcoin-up-or-down-on-{month}-{day}-{year}
     * 24h windows run from 16:00 UTC to 16:00 UTC.
     * The slug date is the end-of-window date in Eastern Time.
     */
    public static String generate24hSlug(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime windowEnd;
        if (utc.getHour() >= 16) {
            windowEnd = utc.toLocalDate().plusDays(1).atTime(16, 0).atZone(ZoneOffset.UTC);
        } else {
            windowEnd = utc.toLocalDate().atTime(16, 0).atZone(ZoneOffset.UTC);
        }

        ZonedDateTime endInEt = windowEnd.withZoneSameInstant(ET);
        String month = endInEt.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = endInEt.getDayOfMonth();
        int year = endInEt.getYear();

        return String.format("bitcoin-up-or-down-on-%s-%d-%d", month, day, year);
    }

    // ── ETH slug generators ──

    /**
     * 5-minute ETH market slug: eth-updown-5m-{epoch}
     */
    public static String generateEth5mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_5M) * SECONDS_5M;
        return "eth-updown-5m-" + slot;
    }

    /**
     * 15-minute ETH market slug: eth-updown-15m-{epoch}
     */
    public static String generateEth15mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_15M) * SECONDS_15M;
        return "eth-updown-15m-" + slot;
    }

    /**
     * 1-hour ETH market slug: ethereum-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
     */
    public static String generateEth1hSlug(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int year = startOfHour.getYear();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("ethereum-up-or-down-%s-%d-%d-%d%s-et",
                month, day, year, displayHour, ampm);
    }

    /**
     * 1-hour ETH market slug without year: ethereum-up-or-down-{month}-{day}-{hour}{am/pm}-et
     */
    public static String generateEth1hSlugNoYear(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("ethereum-up-or-down-%s-%d-%d%s-et",
                month, day, displayHour, ampm);
    }

    /**
     * 4-hour ETH market slug: eth-updown-4h-{epoch}
     */
    public static String generateEth4hSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_4H) * SECONDS_4H;
        return "eth-updown-4h-" + slot;
    }

    /**
     * 24-hour ETH market slug: ethereum-up-or-down-on-{month}-{day}-{year}
     */
    public static String generateEth24hSlug(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime windowEnd;
        if (utc.getHour() >= 16) {
            windowEnd = utc.toLocalDate().plusDays(1).atTime(16, 0).atZone(ZoneOffset.UTC);
        } else {
            windowEnd = utc.toLocalDate().atTime(16, 0).atZone(ZoneOffset.UTC);
        }

        ZonedDateTime endInEt = windowEnd.withZoneSameInstant(ET);
        String month = endInEt.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = endInEt.getDayOfMonth();
        int year = endInEt.getYear();

        return String.format("ethereum-up-or-down-on-%s-%d-%d", month, day, year);
    }

    // ── SOL slug generators ──

    /**
     * 5-minute SOL market slug: sol-updown-5m-{epoch}
     */
    public static String generateSol5mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_5M) * SECONDS_5M;
        return "sol-updown-5m-" + slot;
    }

    /**
     * 15-minute SOL market slug: sol-updown-15m-{epoch}
     */
    public static String generateSol15mSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_15M) * SECONDS_15M;
        return "sol-updown-15m-" + slot;
    }

    /**
     * 1-hour SOL market slug: solana-up-or-down-{month}-{day}-{year}-{hour}{am/pm}-et
     */
    public static String generateSol1hSlug(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int year = startOfHour.getYear();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("solana-up-or-down-%s-%d-%d-%d%s-et",
                month, day, year, displayHour, ampm);
    }

    /**
     * 1-hour SOL market slug without year: solana-up-or-down-{month}-{day}-{hour}{am/pm}-et
     */
    public static String generateSol1hSlugNoYear(Instant now) {
        ZonedDateTime et = now.atZone(ET);
        ZonedDateTime startOfHour = et.truncatedTo(ChronoUnit.HOURS);

        String month = startOfHour.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = startOfHour.getDayOfMonth();
        int hour24 = startOfHour.getHour();
        String ampm = hour24 < 12 ? "am" : "pm";
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("solana-up-or-down-%s-%d-%d%s-et",
                month, day, displayHour, ampm);
    }

    /**
     * 4-hour SOL market slug: sol-updown-4h-{epoch}
     */
    public static String generateSol4hSlug(Instant now) {
        long epoch = now.getEpochSecond();
        long slot = (epoch / SECONDS_4H) * SECONDS_4H;
        return "sol-updown-4h-" + slot;
    }

    /**
     * 24-hour SOL market slug: solana-up-or-down-on-{month}-{day}-{year}
     */
    public static String generateSol24hSlug(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime windowEnd;
        if (utc.getHour() >= 16) {
            windowEnd = utc.toLocalDate().plusDays(1).atTime(16, 0).atZone(ZoneOffset.UTC);
        } else {
            windowEnd = utc.toLocalDate().atTime(16, 0).atZone(ZoneOffset.UTC);
        }

        ZonedDateTime endInEt = windowEnd.withZoneSameInstant(ET);
        String month = endInEt.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase();
        int day = endInEt.getDayOfMonth();
        int year = endInEt.getYear();

        return String.format("solana-up-or-down-on-%s-%d-%d", month, day, year);
    }
}
