package com.nebula.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleAuthRequest {

    @NotBlank(message = "Google credential token is required")
    private String credential;
}
