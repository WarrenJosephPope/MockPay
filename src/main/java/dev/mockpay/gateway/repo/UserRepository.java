package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    /** Callers must lower-case the address first; emails are stored normalised. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
