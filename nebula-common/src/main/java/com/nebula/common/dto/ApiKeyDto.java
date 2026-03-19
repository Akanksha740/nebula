package com.nebula.common.dto;

import lombok.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyDto {
    private UUID id;
    private String keyPrefix;
    private String name;
    private Set<String> permissions;
    private Boolean isActive;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private Instant createdAt;
}
