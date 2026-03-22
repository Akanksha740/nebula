package com.nebula.api.repository;

import com.nebula.common.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    @Query("SELECT c FROM Customer c WHERE c.emailVerificationToken = :token")
    Optional<Customer> findByEmailVerificationToken(@Param("token") String token);

    boolean existsByEmail(String email);

    @Query("SELECT c FROM Customer c WHERE c.passwordResetToken = :token")
    Optional<Customer> findByPasswordResetToken(@Param("token") String token);
}
