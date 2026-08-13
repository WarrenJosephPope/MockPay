package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.PaymentIntent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, String>,
        // Dynamic filtering for the dashboard. A finder method per combination of status, date
        // range, amount and last4 would be dozens of methods; a Specification composes them.
        JpaSpecificationExecutor<PaymentIntent> {

    /** Tenant-scoped lookup. The only one controllers are allowed to call. */
    Optional<PaymentIntent> findByIdAndMerchantId(String id, String merchantId);

    Page<PaymentIntent> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Page<PaymentIntent> findByMerchantIdAndStatusOrderByCreatedAtDesc(
            String merchantId, PaymentIntent.Status status, Pageable pageable);

    /** Drives the sweeper that voids authorisations nobody ever captured. */
    List<PaymentIntent> findByStatusAndCreatedAtBefore(PaymentIntent.Status status, Instant before);

    List<PaymentIntent> findByMerchantIdAndStatusAndCapturedAtBetween(
            String merchantId, PaymentIntent.Status status, Instant from, Instant to);
}
