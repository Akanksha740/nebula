package com.nebula.api.repository;

import com.nebula.common.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByStripeCustomerId(String stripeCustomerId);

    Optional<Customer> findByEmailVerificationToken(String emailVerificationToken);

    boolean existsByEmail(String email);
}
