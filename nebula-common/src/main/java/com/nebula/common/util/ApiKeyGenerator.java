package com.nebula.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class ApiKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "nb_live_";
    private static final int KEY_LENGTH = 32;

    private ApiKeyGenerator() {}

    public static GeneratedKey generate() {
        byte[] randomBytes = new byte[KEY_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
        
        String hash = hash(rawKey);
        String prefix = rawKey.substring(0, 12);
        
        return new GeneratedKey(rawKey, hash, prefix);
    }

    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record GeneratedKey(
        String rawKey,
        String hash,
        String prefix
    ) {}
}
