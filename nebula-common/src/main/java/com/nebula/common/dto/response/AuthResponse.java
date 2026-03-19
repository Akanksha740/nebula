package com.nebula.common.dto.response;

import com.nebula.common.dto.CustomerDto;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private CustomerDto customer;

    public static AuthResponse of(String accessToken, long expiresIn, CustomerDto customer) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .customer(customer)
                .build();
    }
}
