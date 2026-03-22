package com.nebula.api.service;

import com.nebula.api.repository.ApiKeyRepository;
import com.nebula.common.dto.ApiKeyDto;
import com.nebula.common.dto.request.CreateApiKeyRequest;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.config.TierConfig;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.ResourceNotFoundException;
import com.nebula.common.exception.ValidationException;
import com.nebula.common.util.ApiKeyGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private final ApiKeyRepository apiKeyRepository;

    @Transactional(readOnly = true)
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return apiKeyRepository.findByKeyHash(keyHash);
    }

    @Transactional
    public void updateLastUsed(UUID apiKeyId) {
        apiKeyRepository.updateLastUsedAt(apiKeyId, Instant.now());
    }

    @Transactional
    public ApiKeyGenerator.GeneratedKey createApiKey(Customer customer, CreateApiKeyRequest request) {
        int maxKeys = TierConfig.getLimits(customer.getTier()).maxApiKeys();
        long activeKeys = apiKeyRepository.countActiveKeysByCustomerId(customer.getId());
        if (activeKeys >= maxKeys) {
            throw new ValidationException("Maximum number of API keys reached (" + maxKeys + "). Upgrade your plan for more.");
        }

        ApiKeyGenerator.GeneratedKey generated = ApiKeyGenerator.generate();

        Set<String> permissions = request.getPermissions() != null 
            ? request.getPermissions() 
            : Set.of("read");

        ApiKey apiKey = ApiKey.builder()
                .customer(customer)
                .keyHash(generated.hash())
                .keyPrefix(generated.prefix())
                .name(request.getName())
                .permissions(permissions)
                .expiresAt(request.getExpiresAt())
                .isActive(true)
                .build();

        apiKeyRepository.save(apiKey);
        log.info("Created API key '{}' for customer: {}", request.getName(), customer.getEmail());

        return generated;
    }

    public List<ApiKeyDto> getCustomerApiKeys(UUID customerId) {
        return apiKeyRepository.findByCustomerId(customerId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void revokeApiKey(UUID customerId, UUID apiKeyId) {
        int updated = apiKeyRepository.revokeKey(apiKeyId, customerId);
        if (updated == 0) {
            throw new ResourceNotFoundException("API Key", apiKeyId.toString());
        }
        log.info("Revoked API key: {} for customer: {}", apiKeyId, customerId);
    }

    private ApiKeyDto toDto(ApiKey apiKey) {
        return ApiKeyDto.builder()
                .id(apiKey.getId())
                .keyPrefix(apiKey.getKeyPrefix())
                .name(apiKey.getName())
                .permissions(apiKey.getPermissions())
                .isActive(apiKey.getIsActive())
                .lastUsedAt(apiKey.getLastUsedAt())
                .expiresAt(apiKey.getExpiresAt())
                .createdAt(apiKey.getCreatedAt())
                .build();
    }
}
