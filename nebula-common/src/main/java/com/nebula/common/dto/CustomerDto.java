package com.nebula.common.dto;

import com.nebula.common.entity.Customer;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDto {
    private UUID id;
    private String email;
    private String companyName;
    private Customer.SubscriptionTier tier;
    private Boolean isActive;
    private Boolean emailVerified;
    private Instant proTrialExpiresAt;
    private Instant createdAt;
}
