package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.PendingAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PendingActionRepository extends JpaRepository<PendingAction, String> {

    Optional<PendingAction> findByPaymentIntentIdAndConsumedFalse(String paymentIntentId);

    /** Feeds the expiry sweeper: collect requests nobody ever approved. */
    List<PendingAction> findByConsumedFalseAndExpiresAtBefore(Instant now);
}
