package com.nebula.api.security;

import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

@Getter
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final Customer customer;
    private final ApiKey apiKey;

    public ApiKeyAuthenticationToken(Customer customer, ApiKey apiKey) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + customer.getTier().name())));
        this.customer = customer;
        this.apiKey = apiKey;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return apiKey.getKeyHash();
    }

    @Override
    public Object getPrincipal() {
        return customer;
    }
}
