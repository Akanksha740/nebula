package com.nebula.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateApiKeyRequest {
    
    @NotBlank(message = "API key name is required")
    @Size(max = 100, message = "Name must be less than 100 characters")
    private String name;
    
    private Set<String> permissions;
    
    private Instant expiresAt;
}
