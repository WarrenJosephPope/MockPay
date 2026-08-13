package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, String> {

    /** Read on every event emission, to decide who gets a copy. */
    List<WebhookEndpoint> findByMerchantIdAndEnabledTrue(String merchantId);

    List<WebhookEndpoint> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    Optional<WebhookEndpoint> findByIdAndMerchantId(String id, String merchantId);
}
