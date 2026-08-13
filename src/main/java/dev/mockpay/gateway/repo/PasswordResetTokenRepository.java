package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Used to burn every other outstanding token when one is consumed. */
    List<PasswordResetToken> findByUserIdAndUsedAtIsNull(String userId);

    List<PasswordResetToken> findByExpiresAtBefore(Instant cutoff);
}
