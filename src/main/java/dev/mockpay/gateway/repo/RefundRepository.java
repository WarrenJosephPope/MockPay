package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, String> {

    Optional<Refund> findByIdAndMerchantId(String id, String merchantId);

    List<Refund> findByPaymentIntentIdOrderByCreatedAtAsc(String paymentIntentId);

    List<Refund> findByMerchantIdAndStatusAndCreatedAtBetween(
            String merchantId, Refund.Status status, Instant from, Instant to);
}
